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
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
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
    private val diagnosticLogger: DiagnosticLogger
) : ViewModel() {

    private val _isExpanded = savedStateHandle.getStateFlow("is_expanded", false)
    private val _showAdvancedControls = MutableStateFlow(false)

    private val _interactionError = MutableSharedFlow<String>(replay = 0)
    val interactionError: SharedFlow<String> = _interactionError.asSharedFlow()

    private val _navigateToPublish = MutableSharedFlow<String>(replay = 0)
    val navigateToPublish: SharedFlow<String> = _navigateToPublish.asSharedFlow()

    private val _shareEvent = MutableSharedFlow<SharePayload>(replay = 0)
    val shareEvent: SharedFlow<SharePayload> = _shareEvent.asSharedFlow()

    private val _currentPlayableArtifact = MutableStateFlow<PlayableArtifact?>(null)
    private val _loadState = MutableStateFlow(PlayerLoadState.IDLE)
    private val _loadError = MutableStateFlow<String?>(null)

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
                
                if (artifact != null && currentPlayable != null && artifact.id != currentPlayable.id) {
                    diagnosticLogger.info(DiagnosticCategory.SYNC, "PLAYER_ID_MISMATCH_PURGE", mapOf("staleId" to currentPlayable.id, "newId" to artifact.id))
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
        diagnosticLogger.info(DiagnosticCategory.PLAYER, "PLAYER_RESET_STATE")
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
        _showAdvancedControls
    ) { artifact, md, expanded, advanced ->
        val isOwner = artifact?.userId == authRepository.currentUserId
        val isMetadataSynced = artifact != null && md.artifactId == artifact.id
        val mode = when {
            artifact == null -> PlayerMode.HIDDEN
            expanded -> PlayerMode.FULLSCREEN
            else -> PlayerMode.MINI
        }

        val internalOwnerId = artifact?.userId ?: ""
        if (artifact != null && internalOwnerId.isEmpty()) {
            diagnosticLogger.warn(DiagnosticCategory.PLAYER, "PLAYER_INTERNAL_OWNER_ID_EMPTY", mapOf(LogKeys.ARTIFACT_ID to artifact.id))
        }

        PlayerStaticState(
            artifact = artifact?.toPlayerArtifact(),
            internalOwnerId = internalOwnerId,
            isOwner = isOwner,
            isDraft = artifact?.isDraft == true,
            recommendationState = artifact?.recommendationState ?: RecommendationState.ACTIVE,
            isResonated = if (isMetadataSynced) md.isResonated else false,
            resonanceSyncStatus = if (isMetadataSynced) md.resonanceSyncStatus else InteractionSyncStatus.SYNCED,
            selectedReactionType = md.selectedReactionType,
            isResonating = if (isMetadataSynced) md.isResonating else false,
            followSyncStatus = if (isMetadataSynced) md.followSyncStatus else InteractionSyncStatus.SYNCED,
            isSaved = if (isMetadataSynced) md.isSaved else false,
            saveSyncStatus = if (isMetadataSynced) md.saveSyncStatus else InteractionSyncStatus.SYNCED,
            resonanceSummary = if (isMetadataSynced) md.resonanceSummary else "",
            playerMode = mode,
            isExpanded = expanded,
            showAdvancedControls = advanced
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
        _loadError,
        _currentPlayableArtifact
    ) { params ->
        val static = params[0] as PlayerStaticState
        val dynamic = params[1] as PlayerDynamicState
        val review = params[2] as ReviewState
        val listenerReview = params[3] as com.saurabh.artifact.audio.validation.ReviewProgress?
        val loadState = params[4] as PlayerLoadState
        val loadError = params[5] as String?
        val playable = params[6] as PlayableArtifact?

        val artifact = static.artifact

        if (artifact != null && playable != null && playable.originalArtifact == null) {
            diagnosticLogger.trace(DiagnosticCategory.PLAYER, "PLAYER_PLAYABLE_ORIGINAL_MISSING", mapOf(LogKeys.ARTIFACT_ID to artifact.id))
        }

        val isReviewMatching = artifact != null && review.artifactId == artifact.id
        val isListenerReviewMatching = artifact != null && listenerReview?.artifactId == artifact.id
        
        PlayerUiState(
            currentArtifact = artifact,
            internalOwnerId = static.internalOwnerId,
            currentPlayableArtifact = playable,
            loadState = loadState,
            error = loadError,
            isPlaying = dynamic.isPlaying,
            isBuffering = dynamic.isBuffering,
            currentPosition = dynamic.currentPosition,
            durationMs = dynamic.durationMs,
            playbackSpeed = dynamic.playbackSpeed,
            playbackProgress = dynamic.playbackProgress,
            listeningProgress = dynamic.listeningProgress,
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
            recommendationState = static.recommendationState,
            resonanceSummary = static.resonanceSummary,
            isSilenceSkipEnabled = dynamic.isSilenceSkipEnabled,
            sleepTimerMillisRemaining = dynamic.sleepTimerMillisRemaining,
            currentTranscriptSegment = dynamic.currentTranscriptSegment,
            showAdvancedControls = static.showAdvancedControls,
            
            // DECISION: Map progress based on whether it's a draft review or a listener unlock
            coveragePercent = if (static.isDraft) {
                if (isReviewMatching) review.coveragePercent else 0f
            } else {
                if (isListenerReviewMatching) listenerReview?.coveragePercent ?: 0f else 0f
            },
            isThresholdMet = if (static.isDraft) {
                if (isReviewMatching) review.isThresholdMet else false
            } else {
                if (isListenerReviewMatching) listenerReview?.isValidationMet ?: false else false
            },
            isPlaybackEnded = if (static.isDraft) {
                if (isReviewMatching) review.isPlaybackEnded else false
            } else {
                if (isListenerReviewMatching) listenerReview?.hasReachedEnd ?: false else false
            },
            requiredCoverage = publishingPolicy.minCoverage,
            isReachedEndRequired = publishingPolicy.requireReachedEnd
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PlayerUiState()
    )

    /**
     * Derived state for review interactions. Narrowed to prevent unnecessary recompositions 
     * in the ReviewInteractionLayer during playback position updates.
     */
    val reviewInteractionState: StateFlow<ReviewInteractionUiState> = uiState
        .map { state ->
            ReviewInteractionUiState(
                coveragePercent = state.coveragePercent,
                isThresholdMet = state.isThresholdMet,
                isPlaybackEnded = state.isPlaybackEnded,
                requiredCoverage = state.requiredCoverage,
                isReachedEndRequired = state.isReachedEndRequired
            )
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReviewInteractionUiState())

    init {
        // Structural Diagnostic Logging (Phase 1 Refinement)
        viewModelScope.launch {
            uiState.map { state ->
                PlayerDiagnosticState(
                    artifactId = state.currentArtifact?.id,
                    isExpanded = state.isExpanded,
                    playerMode = state.playerMode,
                    isOwner = state.isOwner,
                    isThresholdMet = state.isThresholdMet,
                    loadState = state.loadState
                )
            }
            .distinctUntilChanged()
            .collect { diagnosticState ->
                diagnosticLogger.debug(
                    DiagnosticCategory.PLAYER,
                    "PLAYER_UI_STRUCTURE_CHANGED",
                    mapOf(
                        LogKeys.ARTIFACT_ID to (diagnosticState.artifactId ?: "null"),
                        "isExpanded" to diagnosticState.isExpanded,
                        "playerMode" to diagnosticState.playerMode.name,
                        "isOwner" to diagnosticState.isOwner,
                        "isThresholdMet" to diagnosticState.isThresholdMet,
                        "loadState" to diagnosticState.loadState.name
                    )
                )
            }
        }

        viewModelScope.launch {
            reviewSessionManager.reviewProgress
                .map { it.artifactId to it.isThresholdMet }
                .distinctUntilChanged()
                .collect { (artifactId, isThresholdMet) ->
                    if (isThresholdMet && artifactId != null) {
                        val active = playbackCoordinator.activePlayback.value
                        diagnosticLogger.debug(
                            DiagnosticCategory.PLAYER, 
                            "NAVIGATE_TO_PUBLISH_CHECK", 
                            mapOf(
                                "playbackType" to (active?.playbackType?.name ?: "null"), 
                                LogKeys.ARTIFACT_ID to artifactId
                            )
                        )
                        
                        if (active?.artifactId == artifactId && active.playbackType == com.saurabh.artifact.audio.PlaybackType.DRAFT_PREVIEW) {
                            _navigateToPublish.emit(artifactId)
                        }
                    }
                }
        }
    }

    fun toggleResonate(type: ReactionType = ReactionType.I_HEAR_YOU) {
        val artifactId = uiState.value.currentArtifact?.id ?: return
        val userId = authRepository.currentUser.value?.uid ?: return

        // REFACTOR: Optimistic state is now handled by ReactionRepository -> PendingInteractionDao -> UseCase
        viewModelScope.launch {
            reactionUseCase.get().toggleReaction(artifactId, userId, type).onFailure { error ->
                _interactionError.emit("Could not resonate: ${error.message}")
            }
        }
    }

    fun toggleResonanceConnection() {
        val artifact = uiState.value.currentArtifact ?: return
        val currentUserId = authRepository.currentUser.value?.uid ?: run {
            diagnosticLogger.warn(
                DiagnosticCategory.RESONANCE, 
                "RESONANCE_CONNECTION_BLOCKED", 
                mapOf("reason" to "USER_NULL")
            )
            return
        }
        val ownerId = uiState.value.internalOwnerId
        if (ownerId == currentUserId) return

        val wasResonating = metadata.value.isResonating
        
        // REFACTOR: Optimistic state handled by interaction DAO layer
        viewModelScope.launch {
            playerInteractionUseCase.get().toggleResonanceConnection(currentUserId, ownerId, wasResonating)
                .onFailure { error ->
                    _interactionError.emit("Resonance failed: ${error.message}")
                }
        }
    }

    fun toggleSave() {
        // REFACTOR: We need the original Artifact for toggleSave as it's passed to SavedArtifactManager
        // But the UseCase can be refactored to take ID. For now, we fetch from coordinator if needed
        // or rely on the fact that toggleSave in UseCase takes Artifact.
        // Actually, PlayerInteractionUseCase.toggleSave(Artifact) is called.
        // We'll use a safer approach: get the artifact from the coordinator.
        val artifact = playbackCoordinator.currentArtifact.value ?: return

        viewModelScope.launch {
            playerInteractionUseCase.get().toggleSave(artifact)
        }
    }

    fun playArtifact(artifact: Artifact, collection: List<Artifact> = emptyList()) {
        diagnosticLogger.debug(DiagnosticCategory.NAVIGATION, "PLAYER_SCREEN_ENTERED", mapOf("source" to "playArtifact"))
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
            diagnosticLogger.debug(DiagnosticCategory.NAVIGATION, "PLAYER_SCREEN_ENTERED", mapOf("source" to "playArtifactById"))
            setExpanded(true)
            _loadState.value = PlayerLoadState.LOADING
            _loadError.value = null
            
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
                    
                    diagnosticLogger.error(
                        category = DiagnosticCategory.PLAYER,
                        eventName = "PLAYER_LOAD_FAILED",
                        metadata = mapOf(
                            LogKeys.ARTIFACT_ID to artifactId,
                            "currentlyPlayingArtifactId" to (playbackCoordinator.currentArtifact.value?.id ?: "none"),
                            "loadState_before" to "LOADING",
                            "loadState_after" to "ERROR",
                            "error" to error.toString()
                        )
                    )

                    val userMessage = when (error) {
                        is com.saurabh.artifact.model.AppError.NotFound -> "This artifact is no longer available."
                        is com.saurabh.artifact.model.AppError.PermissionDenied -> "This artifact isn't available to you."
                        is com.saurabh.artifact.model.AppError.NetworkFailure -> "Connection lost. Please check your network."
                        else -> "Failed to load artifact."
                    }
                    _loadError.value = userMessage
                    _interactionError.emit(userMessage)
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
        val artifact = playbackCoordinator.currentArtifact.value ?: return
        
        // Phase 6: Action Safety
        if (!artifact.isDraft) {
            diagnosticLogger.warn(
                DiagnosticCategory.PLAYER, 
                "DELETE_BLOCKED_PUBLISHED", 
                mapOf(LogKeys.ARTIFACT_ID to artifact.id)
            )
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
        val isDraft = uiState.value.isOwner // For drafts, ownership implies draft state in this context
        // Actually, we should use the internal state for safety
        val originalArtifact = playbackCoordinator.currentArtifact.value
        if (originalArtifact?.isDraft == true) {
            setExpanded(false)
            onNavigate(originalArtifact.id)
        } else {
            diagnosticLogger.warn(
                DiagnosticCategory.NAVIGATION, 
                "EDIT_NAVIGATION_BLOCKED", 
                mapOf(
                    LogKeys.ARTIFACT_ID to (artifact?.id ?: "null"), 
                    "reason" to "NON_DRAFT"
                )
            )
        }
    }

    fun onPublishClick(onNavigate: (String) -> Unit) {
        val originalArtifact = playbackCoordinator.currentArtifact.value
        val isThresholdMet = uiState.value.isThresholdMet
        if (originalArtifact?.isDraft == true && isThresholdMet) {
            setExpanded(false)
            onNavigate(originalArtifact.id)
        } else {
            diagnosticLogger.warn(
                DiagnosticCategory.NAVIGATION, 
                "PUBLISH_NAVIGATION_BLOCKED", 
                mapOf(
                    LogKeys.ARTIFACT_ID to (originalArtifact?.id ?: "null"), 
                    "isDraft" to (originalArtifact?.isDraft ?: false), 
                    "isThresholdMet" to isThresholdMet
                )
            )
        }
    }

    fun onShareClicked() {
        val artifact = uiState.value.currentArtifact ?: return
        
        // Phase 6: Safety check using centralized eligibility
        if (!com.saurabh.artifact.util.ShareEligibility.canShare(isPublic = true, isDraft = artifact.isDraft)) {
            return
        }

        viewModelScope.launch {
            _shareEvent.emit(
                SharePayload(
                    artifactId = artifact.id,
                    title = artifact.title,
                    authorName = artifact.author.name,
                    authorSigil = artifact.author.sigil
                )
            )
        }
    }

    fun startSleepTimer(minutes: Int) {
        playbackCoordinator.startSleepTimer(minutes.minutes)
    }
}

private data class PlayerDiagnosticState(
    val artifactId: String?,
    val isExpanded: Boolean,
    val playerMode: PlayerMode,
    val isOwner: Boolean,
    val isThresholdMet: Boolean,
    val loadState: PlayerLoadState
)

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
    val artifact: PlayerArtifact? = null,
    val internalOwnerId: String = "",
    val isOwner: Boolean = false,
    val isDraft: Boolean = false,
    val recommendationState: RecommendationState = RecommendationState.ACTIVE,
    val isResonated: Boolean = false,
    val resonanceSyncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED,
    val selectedReactionType: ReactionType = ReactionType.I_HEAR_YOU,
    val isResonating: Boolean = false,
    val followSyncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED,
    val isSaved: Boolean = false,
    val saveSyncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED,
    val resonanceSummary: String = "",
    val playerMode: PlayerMode = PlayerMode.HIDDEN,
    val isExpanded: Boolean = false,
    val showAdvancedControls: Boolean = false
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
