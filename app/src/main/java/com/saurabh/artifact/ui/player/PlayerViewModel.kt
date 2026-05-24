package com.saurabh.artifact.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.PlaybackSessionManager
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.repository.ReactionRepository
import com.saurabh.artifact.repository.SavedArtifactManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackSessionManager: PlaybackSessionManager,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val reactionRepository: ReactionRepository,
    private val savedArtifactManager: SavedArtifactManager,
    private val recordingRepository: com.saurabh.artifact.repository.RecordingRepository,
    private val commentUnlockRepository: com.saurabh.artifact.repository.CommentUnlockRepository,
    private val reviewSessionManager: com.saurabh.artifact.audio.ReviewSessionManager
) : ViewModel() {

    private val _isExpanded = MutableStateFlow(false)
    private val _showAdvancedControls = MutableStateFlow(false)
    
    private val _isCommentUnlocked = MutableStateFlow(false)
    private val _isResonated = MutableStateFlow(false)
    private val _selectedReactionType = MutableStateFlow(ReactionType.I_HEAR_YOU)
    private val _isFollowed = MutableStateFlow(false)
    private val _isSaved = MutableStateFlow(false)
    private val _resonanceSummary = MutableStateFlow("")
    private val _commentCount = MutableStateFlow(0)

    private val _sleepTimerMillisRemaining = MutableStateFlow<Long?>(null)
    private var sleepTimerJob: Job? = null

    init {
        // Reset player state on logout
        viewModelScope.launch {
            authRepository.currentUser.collect { user ->
                if (user == null) {
                    resetState()
                }
            }
        }

        // Update interaction state when artifact changes
        viewModelScope.launch {
            playbackSessionManager.currentArtifact.collectLatest { artifact ->
                if (artifact != null) {
                    val currentUserId = authRepository.currentUser.value?.uid
                    
                    if (currentUserId != null) {
                        launch {
                            reactionRepository.getArtifactReactions(artifact.id, currentUserId).collect { reactions ->
                                val userReaction = reactions.firstOrNull()
                                _isResonated.value = userReaction != null
                                if (userReaction != null) {
                                    _selectedReactionType.value = ReactionType.fromId(userReaction.typeId)
                                }
                            }
                        }
                        
                        launch {
                            reactionRepository.getReactionCounts(artifact.id).collect { counts ->
                                val isOwner = artifact.userId == currentUserId
                                _resonanceSummary.value = counts?.getFuzzySummary(isOwner) 
                                    ?: com.saurabh.artifact.model.ArtifactReactionCounts(
                                        artifactId = artifact.id,
                                        totalCount = artifact.reactionCount,
                                        visibility = artifact.reactionVisibility
                                    ).getFuzzySummary(isOwner)
                            }
                        }
                    }

                    if (currentUserId != null && artifact.userId != currentUserId) {
                        launch {
                            userRepository.observeIsFollowing(currentUserId, artifact.userId).collect {
                                _isFollowed.value = it
                            }
                        }
                    } else {
                        _isFollowed.value = false
                    }

                    launch {
                        savedArtifactManager.savedIds.collect { savedIds ->
                            _isSaved.value = savedIds.contains(artifact.id)
                        }
                    }

                    _commentCount.value = artifact.commentCount

                    launch {
                        commentUnlockRepository.isUnlocked(artifact.id).collect { unlocked ->
                            _isCommentUnlocked.value = unlocked
                        }
                    }
                } else {
                    _isResonated.value = false
                    _isFollowed.value = false
                    _isSaved.value = false
                    _resonanceSummary.value = ""
                    _commentCount.value = 0
                    _isCommentUnlocked.value = false
                }
            }
        }

        // Update unlock state if threshold met
        viewModelScope.launch {
            reviewSessionManager.reviewProgress.collect { session ->
                if (session.isThresholdMet) {
                    val artifactId = session.artifactId
                    if (artifactId != null && !_isCommentUnlocked.value) {
                        _isCommentUnlocked.value = true
                        commentUnlockRepository.unlockArtifact(artifactId)
                    }
                }
            }
        }
    }

    private fun resetState() {
        _isExpanded.value = false
        _showAdvancedControls.value = false
        _isCommentUnlocked.value = false
        _sleepTimerMillisRemaining.value = null
        sleepTimerJob?.cancel()
        playbackSessionManager.stop()
    }
    
    val uiState: StateFlow<PlayerUiState> = combine(
        playbackSessionManager.currentArtifact,
        playbackSessionManager.isPlaying,
        playbackSessionManager.isBuffering,
        playbackSessionManager.currentPosition,
        playbackSessionManager.duration,
        playbackSessionManager.playbackSpeed,
        playbackSessionManager.isSkipSilenceEnabled,
        _isCommentUnlocked,
        _isExpanded,
        _showAdvancedControls,
        _sleepTimerMillisRemaining,
        _isResonated,
        _selectedReactionType,
        _isFollowed,
        _isSaved,
        _resonanceSummary,
        _commentCount,
        reviewSessionManager.reviewProgress
    ) { params: Array<Any?> ->
        val artifact = params[0] as Artifact?
        val isPlaying = params[1] as Boolean
        val isBuffering = params[2] as Boolean
        val position = params[3] as Long
        val duration = params[4] as Long
        val speed = params[5] as Float
        val isSkipSilenceEnabled = params[6] as Boolean
        val commentUnlocked = params[7] as Boolean
        val expanded = params[8] as Boolean
        val showAdvanced = params[9] as Boolean
        val sleepTimer = params[10] as Long?
        val isResonated = params[11] as Boolean
        val selectedReactionType = params[12] as ReactionType
        val isFollowed = params[13] as Boolean
        val isSaved = params[14] as Boolean
        val resonanceSummary = params[15] as String
        val commentCount = params[16] as Int
        val reviewState = params[17] as com.saurabh.artifact.audio.ReviewState

        val progress = if (duration > 0) position.toFloat() / duration else 0f
        val furthestProgress = if (reviewState.artifactId == artifact?.id) reviewState.progress else 0f

        val currentSegment = artifact?.transcript?.find { position in it.startMs..it.endMs }

        val mode = when {
            artifact == null -> PlayerMode.HIDDEN
            expanded -> PlayerMode.FULLSCREEN
            else -> PlayerMode.MINI
        }

        PlayerUiState(
            currentArtifact = artifact,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            currentPosition = position,
            duration = duration,
            playbackSpeed = speed,
            isCommentUnlocked = commentUnlocked,
            listeningProgress = furthestProgress, 
            isExpanded = expanded,
            playerMode = mode,
            isResonated = isResonated,
            selectedReactionType = selectedReactionType,
            isFollowed = isFollowed,
            isSaved = isSaved,
            resonanceSummary = resonanceSummary,
            commentCount = commentCount,
            isSilenceSkipEnabled = isSkipSilenceEnabled,
            sleepTimerMillisRemaining = sleepTimer,
            currentTranscriptSegment = currentSegment,
            showAdvancedControls = showAdvanced
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PlayerUiState()
    )

    fun toggleResonate(type: ReactionType = _selectedReactionType.value) {
        val artifact = uiState.value.currentArtifact ?: return
        val userId = authRepository.currentUser.value?.uid ?: return
        viewModelScope.launch {
            reactionRepository.toggleReaction(artifact.id, userId, type)
        }
    }

    fun setReactionType(type: ReactionType) {
        _selectedReactionType.value = type
        if (uiState.value.isResonated) {
            toggleResonate(type)
        }
    }

    fun toggleFollow() {
        val artifact = uiState.value.currentArtifact ?: return
        val currentUserId = authRepository.currentUser.value?.uid ?: return
        if (artifact.userId == currentUserId) return

        viewModelScope.launch {
            if (uiState.value.isFollowed) {
                userRepository.unfollowUser(currentUserId, artifact.userId)
            } else {
                userRepository.followUser(currentUserId, artifact.userId)
            }
        }
    }

    fun toggleSave() {
        val artifact = uiState.value.currentArtifact ?: return
        savedArtifactManager.toggleSave(artifact)
    }

    fun playArtifact(artifact: Artifact) {
        _isCommentUnlocked.value = false // Reset for new artifact
        _isExpanded.value = true // Auto-expand when play is triggered
        playbackSessionManager.play(
            artifact = artifact,
            owner = PlaybackSessionManager.InteractionOwner.PUBLIC_PLAYER
        )
    }

    fun togglePlayPause() {
        playbackSessionManager.togglePlayPause()
    }

    fun seekTo(position: Long) {
        playbackSessionManager.seekTo(position)
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackSessionManager.setPlaybackSpeed(speed)
    }

    fun toggleSilenceSkipping() {
        playbackSessionManager.setSkipSilenceEnabled(!playbackSessionManager.isSkipSilenceEnabled.value)
    }

    fun setExpanded(expanded: Boolean) {
        _isExpanded.value = expanded
    }

    fun setShowAdvancedControls(show: Boolean) {
        _showAdvancedControls.value = show
    }

    fun rewind() {
        val newPos = (playbackSessionManager.currentPosition.value - 10000).coerceAtLeast(0)
        playbackSessionManager.seekTo(newPos)
    }

    fun forward() {
        val newPos = (playbackSessionManager.currentPosition.value + 10000).coerceAtMost(playbackSessionManager.duration.value)
        playbackSessionManager.seekTo(newPos)
    }

    fun deleteCurrentArtifact() {
        val artifact = uiState.value.currentArtifact ?: return
        if (artifact.isDraft) {
            viewModelScope.launch {
                val draft = recordingRepository.getDraft(artifact.id)
                if (draft != null) {
                    recordingRepository.deleteDraft(draft)
                }
                playbackSessionManager.stop()
                _isExpanded.value = false
            }
        }
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes == 0) {
            _sleepTimerMillisRemaining.value = null
            return
        }

        val totalMillis = minutes * 60 * 1000L
        _sleepTimerMillisRemaining.value = totalMillis
        
        sleepTimerJob = viewModelScope.launch {
            var remaining = totalMillis
            while (remaining > 0) {
                delay(1000)
                remaining -= 1000
                _sleepTimerMillisRemaining.value = remaining
                if (!playbackSessionManager.isPlaying.value) continue
            }
            playbackSessionManager.stop()
            _sleepTimerMillisRemaining.value = null
        }
    }
}
