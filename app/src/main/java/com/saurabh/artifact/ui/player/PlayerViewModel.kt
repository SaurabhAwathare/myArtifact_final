package com.saurabh.artifact.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackType
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
    private val playbackCoordinator: PlaybackCoordinator,
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

    // Interaction Reliability (Zero Dead Interactions)
    private val _interactionError = MutableSharedFlow<String>(replay = 0)
    val interactionError: SharedFlow<String> = _interactionError.asSharedFlow()

    private val _optimisticResonanceConnection = MutableStateFlow<Boolean?>(null)
    private val _optimisticSave = MutableStateFlow<Boolean?>(null)
    private val _optimisticResonate = MutableStateFlow<Boolean?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isCommentUnlocked: StateFlow<Boolean> = playbackCoordinator.currentArtifact.flatMapLatest { artifact ->
        if (artifact != null) {
            commentUnlockRepository.isUnlocked(artifact.id)
        } else {
            flowOf(false)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isResonated: StateFlow<Boolean> = combine(
        playbackCoordinator.currentArtifact,
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
        playbackCoordinator.currentArtifact,
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
    val isResonating: StateFlow<Boolean> = combine(
        playbackCoordinator.currentArtifact,
        authRepository.currentUser,
        _optimisticResonanceConnection
    ) { artifact, user, optimistic ->
        if (optimistic != null) return@combine flowOf(optimistic)
        if (artifact != null && user != null && artifact.userId != user.uid) {
            userRepository.observeIsResonating(user.uid, artifact.userId)
        } else {
            flowOf(false)
        }
    }.flatMapLatest { it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isSaved: StateFlow<Boolean> = combine(
        playbackCoordinator.currentArtifact,
        savedArtifactManager.savedIds,
        _optimisticSave
    ) { artifact, savedIds, optimistic ->
        if (optimistic != null) return@combine optimistic
        artifact != null && savedIds.contains(artifact.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val resonanceSummary: StateFlow<String> = playbackCoordinator.currentArtifact.flatMapLatest { artifact ->
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

    val commentCount: StateFlow<Int> = playbackCoordinator.currentArtifact.map { 
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

        // Observe playback errors
        viewModelScope.launch {
            playbackCoordinator.error.collect { errorMessage ->
                _interactionError.emit(errorMessage)
            }
        }

        // Observe playback session status
        viewModelScope.launch {
            playbackCoordinator.activePlayback.collect { active ->
                // Optionally handle playback type changes here
            }
        }
    }

    private fun resetState() {
        _isExpanded.value = false
        _showAdvancedControls.value = false
        _sleepTimerMillisRemaining.value = null
        sleepTimerJob?.cancel()
        playbackCoordinator.stop()
    }
    
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val smoothPosition: Flow<Long> = playbackCoordinator.positionSync.flatMapLatest { sync ->
        if (sync.isPlaying) {
            flow {
                while (true) {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - sync.timestampMs
                    val current = sync.positionMs + (elapsed * sync.speed).toLong()
                    emit(current)
                    delay(32) // ~30fps for smooth UI
                }
            }
        } else {
            flowOf(sync.positionMs)
        }
    }

    val uiState: StateFlow<PlayerUiState> = combine(
        playbackCoordinator.currentArtifact,
        playbackCoordinator.isPlaying,
        playbackCoordinator.isBuffering,
        smoothPosition,
        playbackCoordinator.durationMs,
        playbackCoordinator.playbackSpeed,
        playbackCoordinator.isSkipSilenceEnabled,
        isCommentUnlocked,
        _isExpanded,
        _showAdvancedControls,
        _sleepTimerMillisRemaining,
        isResonated,
        selectedReactionType,
        isResonating,
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
        val isResonating = params[13] as Boolean
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
            durationMs = duration,
            playbackSpeed = speed,
            isCommentUnlocked = commentUnlocked,
            playbackProgress = progress,
            listeningProgress = furthestProgress, 
            isExpanded = expanded,
            playerMode = mode,
            isResonated = isResonated,
            selectedReactionType = selectedReactionType,
            isResonating = isResonating,
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
        
        val wasResonated = uiState.value.isResonated
        _optimisticResonate.value = !wasResonated

        viewModelScope.launch {
            reactionRepository.toggleReaction(artifact.id, userId, type).onFailure { error ->
                _optimisticResonate.value = null
                _interactionError.emit("Could not resonate: ${error.message}")
            }.onSuccess {
                _optimisticResonate.value = null
            }
        }
    }

    fun toggleResonanceConnection() {
        val artifact = uiState.value.currentArtifact ?: return
        val currentUserId = authRepository.currentUser.value?.uid ?: return
        if (artifact.userId == currentUserId) return

        val wasResonating = uiState.value.isResonating
        _optimisticResonanceConnection.value = !wasResonating

        viewModelScope.launch {
            val result = if (wasResonating) {
                userRepository.stopResonatingWithUser(currentUserId, artifact.userId)
            } else {
                userRepository.resonateWithUser(currentUserId, artifact.userId)
            }
            
            result.onFailure { error ->
                _optimisticResonanceConnection.value = null
                _interactionError.emit("Resonance failed: ${error.message}")
            }.onSuccess {
                _optimisticResonanceConnection.value = null
            }
        }
    }

    fun toggleSave() {
        val artifact = uiState.value.currentArtifact ?: return
        val wasSaved = uiState.value.isSaved
        _optimisticSave.value = !wasSaved
        
        viewModelScope.launch {
            try {
                savedArtifactManager.toggleSave(artifact)
                _optimisticSave.value = null
            } catch (e: Exception) {
                _optimisticSave.value = null
                _interactionError.emit("Could not save: ${e.message}")
            }
        }
    }

    fun playArtifact(artifact: Artifact, collection: List<Artifact> = emptyList()) {
        _isExpanded.value = true // Auto-expand when play is triggered
        playbackCoordinator.playArtifact(
            artifact = artifact,
            collection = collection
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
        playbackCoordinator.togglePlayPause()
    }

    fun seekTo(position: Long) {
        playbackCoordinator.seekTo(position)
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackCoordinator.setPlaybackSpeed(speed)
    }

    fun toggleSilenceSkipping() {
        playbackCoordinator.setSkipSilenceEnabled(!playbackCoordinator.isSkipSilenceEnabled.value)
    }

    fun setExpanded(expanded: Boolean) {
        _isExpanded.value = expanded
    }

    fun setShowAdvancedControls(show: Boolean) {
        _showAdvancedControls.value = show
    }

    fun rewind() {
        val newPos = (playbackCoordinator.currentPosition.value - 10000).coerceAtLeast(0)
        playbackCoordinator.seekTo(newPos)
    }

    fun forward() {
        val newPos = (playbackCoordinator.currentPosition.value + 10000).coerceAtMost(playbackCoordinator.durationMs.value)
        playbackCoordinator.seekTo(newPos)
    }

    fun deleteCurrentArtifact() {
        val artifact = uiState.value.currentArtifact ?: return
        
        viewModelScope.launch {
            val result = if (artifact.isDraft) {
                val draft = recordingRepository.getDraft(artifact.id)
                if (draft != null) {
                    recordingRepository.deleteDraft(draft)
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Draft not found"))
                }
            } else {
                artifactRepository.deletePublishedArtifact(artifact.id)
            }

            result.onSuccess {
                playbackCoordinator.stop()
                _isExpanded.value = false
            }.onFailure { e ->
                _interactionError.emit("Unable to delete: ${e.message}")
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
                if (!playbackCoordinator.isPlaying.value) continue
            }
            playbackCoordinator.stop()
            _sleepTimerMillisRemaining.value = null
        }
    }
}
