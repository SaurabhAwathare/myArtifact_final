package com.saurabh.artifact.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.ReviewSessionManager
import com.saurabh.artifact.audio.ReviewState
import com.saurabh.artifact.domain.feed.ReactionUseCase
import com.saurabh.artifact.domain.player.DeleteArtifactUseCase
import com.saurabh.artifact.domain.player.GetPlayerContextUseCase
import com.saurabh.artifact.domain.player.PlayerInteractionUseCase
import com.saurabh.artifact.domain.player.PlayerMetadata
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.PlayableArtifactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val playbackCoordinator: PlaybackCoordinator,
    private val authRepository: AuthRepository,
    private val reactionUseCase: dagger.Lazy<ReactionUseCase>,
    private val playerInteractionUseCase: dagger.Lazy<PlayerInteractionUseCase>,
    getPlayerContextUseCase: GetPlayerContextUseCase,
    private val playableArtifactRepository: dagger.Lazy<PlayableArtifactRepository>,
    private val reviewSessionManager: ReviewSessionManager,
    private val deleteArtifactUseCase: dagger.Lazy<DeleteArtifactUseCase>,
    private val publishingPolicy: com.saurabh.artifact.domain.review.publishing.PublishingReviewPolicy,
    private val commentPolicy: com.saurabh.artifact.domain.review.comments.CommentUnlockPolicy
) : ViewModel() {

    private val _isExpanded = savedStateHandle.getStateFlow("is_expanded", false)
    private val _showAdvancedControls = MutableStateFlow(false)
    private val _showComments = MutableStateFlow(false)

    private val _interactionError = MutableSharedFlow<String>(replay = 0)
    val interactionError: SharedFlow<String> = _interactionError.asSharedFlow()

    private val _navigateToPublish = MutableSharedFlow<String>(replay = 0)
    val navigateToPublish: SharedFlow<String> = _navigateToPublish.asSharedFlow()

    private val _currentPlayableArtifact = MutableStateFlow<PlayableArtifact?>(null)
    private val _loadState = MutableStateFlow(PlayerLoadState.IDLE)

    // Consolidated metadata from UseCase - Live and Atomic
    private val metadata: StateFlow<PlayerMetadata> = getPlayerContextUseCase.execute(
        artifactFlow = playbackCoordinator.currentArtifact
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerMetadata())

    init {
        viewModelScope.launch {
            authRepository.currentUser.collect { user ->
                if (user == null) {
                    resetState()
                }
            }
        }

        viewModelScope.launch {
            playbackCoordinator.error.collect { errorMessage ->
                _interactionError.emit(errorMessage)
            }
        }

        // Phase 1 & 7: State Synchronization and Debug Logging
        viewModelScope.launch {
            playbackCoordinator.currentArtifact.collect { artifact ->
                val currentPlayable = _currentPlayableArtifact.value
                
                android.util.Log.d("PLAYER_SYNC", """
                    Sync Update:
                    - currentArtifact: ${artifact?.id} (isDraft=${artifact?.isDraft})
                    - currentPlayable: ${currentPlayable?.id}
                    - LoadState: ${_loadState.value}
                """.trimIndent())

                if (artifact != null && currentPlayable != null && artifact.id != currentPlayable.id) {
                    android.util.Log.d("PLAYER_SYNC", "ID Mismatch detected. Purging stale playable: ${currentPlayable.id}")
                    _currentPlayableArtifact.value = null
                    _loadState.value = PlayerLoadState.IDLE
                } else if (artifact == null) {
                    _currentPlayableArtifact.value = null
                    _loadState.value = PlayerLoadState.IDLE
                }
            }
        }
    }

    private fun resetState() {
        android.util.Log.d("PLAYER_SYNC", "resetState() called - clearing all player metadata")
        setExpanded(false)
        _showAdvancedControls.value = false
        _currentPlayableArtifact.value = null
        _loadState.value = PlayerLoadState.IDLE
        playbackCoordinator.stop()
    }

    // High-frequency playback state
    private val playbackState = combine(
        playbackCoordinator.currentArtifact,
        playbackCoordinator.isPlaying,
        playbackCoordinator.isBuffering,
        playbackCoordinator.smoothPosition,
        playbackCoordinator.duration,
        playbackCoordinator.playbackSpeed,
        playbackCoordinator.isSkipSilenceEnabled
    ) { params ->
        PlaybackSubState(
            artifact = params[0] as Artifact?,
            isPlaying = params[1] as Boolean,
            isBuffering = params[2] as Boolean,
            position = params[3] as Duration,
            duration = params[4] as Duration,
            speed = params[5] as Float,
            isSilenceSkipEnabled = params[6] as Boolean
        )
    }.distinctUntilChanged()

    // Isolated transcript lookup - Optimized with Binary Search and Distinct emission
    private val currentTranscriptSegment = combine(
        playbackCoordinator.currentArtifact,
        playbackCoordinator.smoothPosition
    ) { artifact, position ->
        artifact?.transcript?.findSegmentAt(position.inWholeMilliseconds)
    }.distinctUntilChanged { old, new ->
        old?.id == new?.id
    }

    private val staticState = combine(
        playbackCoordinator.currentArtifact,
        metadata,
        _isExpanded,
        _showAdvancedControls,
        _showComments
    ) { artifact, md, expanded, advanced, comments ->
        val isOwner = artifact?.userId == authRepository.currentUserId
        val isMetadataSynced = artifact != null && md.artifactId == artifact.id
        val mode = when {
            artifact == null -> PlayerMode.HIDDEN
            expanded -> PlayerMode.FULLSCREEN
            else -> PlayerMode.MINI
        }

        PlayerStaticState(
            artifact = artifact,
            isOwner = isOwner,
            engagementStatus = if (isMetadataSynced) md.engagementStatus else EngagementStatus.LOCKED,
            isResonated = if (isMetadataSynced) md.isResonated else false,
            resonanceSyncStatus = if (isMetadataSynced) md.resonanceSyncStatus else InteractionSyncStatus.SYNCED,
            selectedReactionType = md.selectedReactionType,
            isResonating = if (isMetadataSynced) md.isResonating else false,
            followSyncStatus = if (isMetadataSynced) md.followSyncStatus else InteractionSyncStatus.SYNCED,
            isSaved = if (isMetadataSynced) md.isSaved else false,
            saveSyncStatus = if (isMetadataSynced) md.saveSyncStatus else InteractionSyncStatus.SYNCED,
            resonanceSummary = if (isMetadataSynced) md.resonanceSummary else "",
            commentCount = if (isMetadataSynced) md.commentCount else 0,
            playerMode = mode,
            isExpanded = expanded,
            showAdvancedControls = advanced,
            showComments = comments
        )
    }.distinctUntilChanged()

    private val dynamicState = combine(
        playbackState,
        reviewSessionManager.reviewProgress,
        currentTranscriptSegment,
        playbackCoordinator.sleepTimerRemaining
    ) { pb, reviewState, transcriptSegment, sleepTimer ->
        val progress = if (pb.duration > Duration.ZERO) (pb.position / pb.duration).toFloat() else 0f
        val furthestProgress = if (reviewState.artifactId == pb.artifact?.id) reviewState.progress else 0f

        PlayerDynamicState(
            isPlaying = pb.isPlaying,
            isBuffering = pb.isBuffering,
            currentPosition = pb.position.inWholeMilliseconds,
            durationMs = pb.duration.inWholeMilliseconds,
            playbackSpeed = pb.speed,
            playbackProgress = progress,
            listeningProgress = furthestProgress,
            isSilenceSkipEnabled = pb.isSilenceSkipEnabled,
            sleepTimerMillisRemaining = sleepTimer?.inWholeMilliseconds,
            currentTranscriptSegment = transcriptSegment
        )
    }.distinctUntilChanged()

    val uiState: StateFlow<PlayerUiState> = combine(
        staticState,
        dynamicState,
        reviewSessionManager.reviewProgress,
        playbackCoordinator.currentProgress,
        _loadState,
        _currentPlayableArtifact
    ) { params ->
        val static = params[0] as PlayerStaticState
        val dynamic = params[1] as PlayerDynamicState
        val review = params[2] as ReviewState
        val listenerReview = params[3] as com.saurabh.artifact.audio.validation.ReviewProgress?
        val loadState = params[4] as PlayerLoadState
        val playable = params[5] as PlayableArtifact?

        val artifact = static.artifact
        val isReviewMatching = artifact != null && review.artifactId == artifact.id
        val isListenerReviewMatching = artifact != null && listenerReview?.artifactId == artifact.id
        
        if (artifact != null) {
            android.util.Log.v("PLAYER_SYNC", "UI State Recompute: artifact=${artifact.id}, isDraft=${artifact.isDraft}, isReviewMatching=$isReviewMatching")
        }

        PlayerUiState(
            currentArtifact = artifact,
            currentPlayableArtifact = playable,
            loadState = loadState,
            isPlaying = dynamic.isPlaying,
            isBuffering = dynamic.isBuffering,
            currentPosition = dynamic.currentPosition,
            durationMs = dynamic.durationMs,
            playbackSpeed = dynamic.playbackSpeed,
            playbackProgress = dynamic.playbackProgress,
            listeningProgress = dynamic.listeningProgress,
            engagementStatus = static.engagementStatus,
            isExpanded = static.isExpanded,
            playerMode = static.playerMode,
            isResonated = static.isResonated,
            resonanceSyncStatus = static.resonanceSyncStatus,
            selectedReactionType = static.selectedReactionType,
            isResonating = static.isResonating,
            followSyncStatus = static.followSyncStatus,
            isSaved = static.isSaved,
            saveSyncStatus = static.saveSyncStatus,
            isOwner = static.isOwner,
            resonanceSummary = static.resonanceSummary,
            commentCount = static.commentCount,
            isSilenceSkipEnabled = dynamic.isSilenceSkipEnabled,
            sleepTimerMillisRemaining = dynamic.sleepTimerMillisRemaining,
            currentTranscriptSegment = dynamic.currentTranscriptSegment,
            showAdvancedControls = static.showAdvancedControls,
            showComments = static.showComments,
            
            // DECISION: Map progress based on whether it's a draft review or a listener unlock
            coveragePercent = if (artifact?.isDraft == true) {
                if (isReviewMatching) review.coveragePercent else 0f
            } else {
                if (isListenerReviewMatching) listenerReview?.coveragePercent ?: 0f else 0f
            },
            isThresholdMet = if (artifact?.isDraft == true) {
                if (isReviewMatching) review.isThresholdMet else false
            } else {
                if (isListenerReviewMatching) listenerReview?.isValidationMet ?: false else false
            },
            isPlaybackEnded = if (artifact?.isDraft == true) {
                if (isReviewMatching) review.isPlaybackEnded else false
            } else {
                if (isListenerReviewMatching) listenerReview?.hasReachedEnd ?: false else false
            },
            requiredCoverage = if (artifact?.isDraft == true) publishingPolicy.minCoverage else commentPolicy.minCoverage,
            isReachedEndRequired = if (artifact?.isDraft == true) publishingPolicy.requireReachedEnd else commentPolicy.requireReachedEnd
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PlayerUiState()
    )

    init {
        viewModelScope.launch {
            reviewSessionManager.reviewProgress
                .map { it.artifactId to it.isThresholdMet }
                .distinctUntilChanged()
                .collect { (artifactId, isThresholdMet) ->
                    if (isThresholdMet && artifactId != null) {
                        val active = playbackCoordinator.activePlayback.value
                        android.util.Log.d("LOOP_FIX", "navigateToPublish check: active=${active?.playbackType}, artifact=$artifactId")
                        
                        if (active?.artifactId == artifactId && active.playbackType == com.saurabh.artifact.audio.PlaybackType.DRAFT_PREVIEW) {
                            _navigateToPublish.emit(artifactId)
                        }
                    }
                }
        }
    }

    fun toggleResonate(type: ReactionType = metadata.value.selectedReactionType) {
        val artifact = uiState.value.currentArtifact ?: return
        val userId = authRepository.currentUser.value?.uid ?: return

        // REFACTOR: Optimistic state is now handled by ReactionRepository -> PendingInteractionDao -> UseCase
        viewModelScope.launch {
            reactionUseCase.get().toggleReaction(artifact.id, userId, type).onFailure { error ->
                _interactionError.emit("Could not resonate: ${error.message}")
            }
        }
    }

    fun toggleResonanceConnection() {
        val artifact = uiState.value.currentArtifact ?: return
        val currentUserId = authRepository.currentUser.value?.uid ?: run {
            android.util.Log.w("PlayerViewModel", "Blocked toggleResonanceConnection: User is null.")
            return
        }
        if (artifact.userId == currentUserId) return

        val wasResonating = metadata.value.isResonating
        
        // REFACTOR: Optimistic state handled by interaction DAO layer
        viewModelScope.launch {
            playerInteractionUseCase.get().toggleResonanceConnection(currentUserId, artifact.userId, wasResonating)
                .onFailure { error ->
                    _interactionError.emit("Resonance failed: ${error.message}")
                }
        }
    }

    fun toggleSave() {
        val artifact = uiState.value.currentArtifact ?: return

        viewModelScope.launch {
            playerInteractionUseCase.get().toggleSave(artifact)
        }
    }

    fun playArtifact(artifact: Artifact, collection: List<Artifact> = emptyList()) {
        android.util.Log.d("NAV_TRACE", "Navigate -> Player")
        setExpanded(true)
        _loadState.value = PlayerLoadState.LOADED
        _currentPlayableArtifact.value = null // Clear playable as we have a real artifact
        playbackCoordinator.playArtifact(
            artifact = artifact,
            collection = collection
        )
    }

    fun playArtifactById(artifactId: String, source: PlaybackSource = PlaybackSource.FEED_PLAYBACK) {
        viewModelScope.launch {
            android.util.Log.d("NAV_TRACE", "Navigate -> Player")
            setExpanded(true)
            _loadState.value = PlayerLoadState.LOADING
            
            playableArtifactRepository.get().resolveArtifact(artifactId, source).fold(
                onSuccess = { playable ->
                    _currentPlayableArtifact.value = playable
                    _loadState.value = PlayerLoadState.LOADED
                    
                    // Track resolution success with source context
                    playbackCoordinator.trackPlayableStart(playable)
                    
                    if (playable.originalArtifact != null) {
                        playArtifact(playable.originalArtifact)
                    } else if (playable.originalDraft != null) {
                        // For drafts, we use the reviewSessionManager to handle progress tracking
                        reviewSessionManager.startReview(playable.id)
                    }
                },
                onFailure = { error ->
                    _loadState.value = PlayerLoadState.ERROR
                    _interactionError.emit("Failed to load artifact: ${error.message}")
                }
            )
        }
    }

    fun togglePlayPause() {
        playbackCoordinator.togglePlayPause()
    }

    fun seekTo(position: Long) {
        playbackCoordinator.seekTo(position.milliseconds)
        playbackCoordinator.updateScrubbingPosition(null)
    }

    fun onScrubbing(position: Long) {
        playbackCoordinator.updateScrubbingPosition(position.milliseconds)
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackCoordinator.setPlaybackSpeed(speed)
    }

    fun toggleSilenceSkipping() {
        playbackCoordinator.setSkipSilenceEnabled(!playbackCoordinator.isSkipSilenceEnabled.value)
    }

    fun setExpanded(expanded: Boolean) {
        savedStateHandle["is_expanded"] = expanded
    }

    fun setShowAdvancedControls(show: Boolean) {
        _showAdvancedControls.value = show
    }

    fun setShowComments(show: Boolean) {
        _showComments.value = show
    }

    fun rewind() {
        viewModelScope.launch {
            val currentPos = playbackCoordinator.smoothPosition.first()
            val newPos = (currentPos - 10.seconds).coerceAtLeast(Duration.ZERO)
            playbackCoordinator.seekTo(newPos)
        }
    }

    fun forward() {
        viewModelScope.launch {
            val currentPos = playbackCoordinator.smoothPosition.first()
            val duration = playbackCoordinator.duration.first()
            val newPos = (currentPos + 10.seconds).coerceAtMost(duration)
            playbackCoordinator.seekTo(newPos)
        }
    }

    fun deleteCurrentArtifact() {
        val artifact = uiState.value.currentArtifact ?: return
        
        // Phase 6: Action Safety
        if (!artifact.isDraft) {
            android.util.Log.w("PLAYER_SYNC", "Safety: Blocked attempt to delete published artifact ${artifact.id} via draft flow")
            return
        }

        viewModelScope.launch {
            deleteArtifactUseCase.get().execute(artifact)
                .onSuccess {
                    playbackCoordinator.stop()
                    setExpanded(false)
                }.onFailure { e ->
                    _interactionError.emit("Unable to delete: ${e.message}")
                }
        }
    }

    fun onEditClick(onNavigate: (String) -> Unit) {
        val artifact = uiState.value.currentArtifact
        if (artifact?.isDraft == true) {
            setExpanded(false)
            onNavigate(artifact.id)
        } else {
            android.util.Log.w("PLAYER_SYNC", "Safety: Blocked Edit navigation for non-draft ${artifact?.id}")
        }
    }

    fun onPublishClick(onNavigate: (String) -> Unit) {
        val artifact = uiState.value.currentArtifact
        val isThresholdMet = uiState.value.isThresholdMet
        if (artifact?.isDraft == true && isThresholdMet) {
            setExpanded(false)
            onNavigate(artifact.id)
        } else {
            android.util.Log.w("PLAYER_SYNC", "Safety: Blocked Publish navigation. isDraft=${artifact?.isDraft}, isThresholdMet=$isThresholdMet")
        }
    }

    fun startSleepTimer(minutes: Int) {
        playbackCoordinator.startSleepTimer(minutes.minutes)
    }
}

private data class PlaybackSubState(
    val artifact: Artifact?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val position: Duration,
    val duration: Duration,
    val speed: Float,
    val isSilenceSkipEnabled: Boolean
)

private data class PlayerStaticState(
    val artifact: Artifact? = null,
    val isOwner: Boolean = false,
    val engagementStatus: EngagementStatus = EngagementStatus.LOCKED,
    val isResonated: Boolean = false,
    val resonanceSyncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED,
    val selectedReactionType: ReactionType = ReactionType.I_HEAR_YOU,
    val isResonating: Boolean = false,
    val followSyncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED,
    val isSaved: Boolean = false,
    val saveSyncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED,
    val resonanceSummary: String = "",
    val commentCount: Long = 0,
    val playerMode: PlayerMode = PlayerMode.HIDDEN,
    val isExpanded: Boolean = false,
    val showAdvancedControls: Boolean = false,
    val showComments: Boolean = false
)

private data class PlayerDynamicState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPosition: Long = 0,
    val durationMs: Long = 0,
    val playbackSpeed: Float = 1.0f,
    val playbackProgress: Float = 0f,
    val listeningProgress: Float = 0f,
    val isSilenceSkipEnabled: Boolean = false,
    val sleepTimerMillisRemaining: Long? = null,
    val currentTranscriptSegment: TranscriptSegment? = null
)
