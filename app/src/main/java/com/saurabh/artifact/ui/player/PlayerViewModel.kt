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
    private val artifactRepository: com.saurabh.artifact.repository.ArtifactRepository,
    private val commentUnlockRepository: com.saurabh.artifact.repository.CommentUnlockRepository,
    reviewSessionManager: com.saurabh.artifact.audio.ReviewSessionManager
) : ViewModel() {

    private val _isExpanded = MutableStateFlow(value = false)
    private val _showAdvancedControls = MutableStateFlow(false)
    private val _sleepTimerMillisRemaining = MutableStateFlow<Long?>(null)
    private var sleepTimerJob: Job? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isCommentUnlocked: StateFlow<Boolean> = playbackSessionManager.currentArtifact.flatMapLatest { artifact ->
        if (artifact != null) {
            commentUnlockRepository.isUnlocked(artifact.id)
        } else {
            flowOf(false)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isResonated: StateFlow<Boolean> = combine(
        playbackSessionManager.currentArtifact,
        authRepository.currentUser
    ) { artifact, user ->
        if ((artifact != null) && (user != null)) {
            reactionRepository.getArtifactReactions(artifact.id, user.uid)
                .map { it.isNotEmpty() }
        } else {
            flowOf(false)
        }
    }.flatMapLatest { it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val selectedReactionType: StateFlow<ReactionType> = combine(
        playbackSessionManager.currentArtifact,
        authRepository.currentUser
    ) { artifact, user ->
        if ((artifact != null) && (user != null)) {
            reactionRepository.getArtifactReactions(artifact.id, user.uid)
                .map { reactions ->
                    reactions.firstOrNull()?.let { ReactionType.fromId(it.typeId) } ?: ReactionType.I_HEAR_YOU
                }
        } else {
            flowOf(ReactionType.I_HEAR_YOU)
        }
    }.flatMapLatest { it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReactionType.I_HEAR_YOU)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isFollowed: StateFlow<Boolean> = combine(
        playbackSessionManager.currentArtifact,
        authRepository.currentUser
    ) { artifact, user ->
        if (artifact != null && user != null && artifact.userId != user.uid) {
            userRepository.observeIsFollowing(user.uid, artifact.userId)
        } else {
            flowOf(false)
        }
    }.flatMapLatest { it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isSaved: StateFlow<Boolean> = combine(
        playbackSessionManager.currentArtifact,
        savedArtifactManager.savedIds
    ) { artifact, savedIds ->
        artifact != null && savedIds.contains(artifact.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val resonanceSummary: StateFlow<String> = playbackSessionManager.currentArtifact.flatMapLatest { artifact ->
        if (artifact != null) {
            val currentUserId = authRepository.currentUser.value?.uid
            reactionRepository.getReactionCounts(artifact.id).map { counts ->
                val isOwner = artifact.userId == currentUserId
                counts?.getFuzzySummary(isOwner) 
                    ?: com.saurabh.artifact.model.ArtifactReactionCounts(
                        artifactId = artifact.id,
                        totalCount = artifact.reactionCount,
                        visibility = artifact.reactionVisibility
                    ).getFuzzySummary(isOwner)
            }
        } else {
            flowOf("")
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val commentCount: StateFlow<Int> = playbackSessionManager.currentArtifact.map { 
        it?.commentCount ?: 0 
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        // Reset player state on logout
        viewModelScope.launch {
            authRepository.currentUser.collect { user ->
                if (user == null) {
                    resetState()
                }
            }
        }
    }

    private fun resetState() {
        _isExpanded.value = false
        _showAdvancedControls.value = false
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
        isCommentUnlocked,
        _isExpanded,
        _showAdvancedControls,
        _sleepTimerMillisRemaining,
        isResonated,
        selectedReactionType,
        isFollowed,
        isSaved,
        resonanceSummary,
        commentCount,
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

    fun toggleResonate(type: ReactionType = selectedReactionType.value) {
        val artifact = uiState.value.currentArtifact ?: return
        val userId = authRepository.currentUser.value?.uid ?: return
        viewModelScope.launch {
            reactionRepository.toggleReaction(artifact.id, userId, type)
        }
    }

    fun setReactionType(type: ReactionType) {
        // Local preference update if needed, but here we drive from Repo
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
        _isExpanded.value = true // Auto-expand when play is triggered
        playbackSessionManager.play(
            artifact = artifact,
            owner = PlaybackSessionManager.InteractionOwner.PUBLIC_PLAYER
        )
    }

    fun playArtifactById(artifactId: String) {
        viewModelScope.launch {
            artifactRepository.getArtifact(artifactId).onSuccess { artifact ->
                playArtifact(artifact)
            }
        }
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
