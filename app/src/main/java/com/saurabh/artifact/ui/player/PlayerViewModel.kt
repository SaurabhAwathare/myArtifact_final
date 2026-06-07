package com.saurabh.artifact.ui.player

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
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.model.findSegmentAt
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackCoordinator: PlaybackCoordinator,
    private val authRepository: AuthRepository,
    private val reactionUseCase: ReactionUseCase,
    private val playerInteractionUseCase: PlayerInteractionUseCase,
    getPlayerContextUseCase: GetPlayerContextUseCase,
    private val artifactRepository: ArtifactRepository,
    reviewSessionManager: ReviewSessionManager,
    private val deleteArtifactUseCase: DeleteArtifactUseCase
) : ViewModel() {

    private val _isExpanded = MutableStateFlow(false)
    private val _showAdvancedControls = MutableStateFlow(false)

    private val _interactionError = MutableSharedFlow<String>(replay = 0)
    val interactionError: SharedFlow<String> = _interactionError.asSharedFlow()

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
    }

    private fun resetState() {
        _isExpanded.value = false
        _showAdvancedControls.value = false
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

    // UI Control State
    private val uiControlState = combine(
        _isExpanded,
        _showAdvancedControls,
        playbackCoordinator.sleepTimerRemaining
    ) { expanded, advanced, timer ->
        UiControlSubState(
            isExpanded = expanded,
            showAdvancedControls = advanced,
            sleepTimerRemaining = timer
        )
    }.distinctUntilChanged()

    // Isolated transcript lookup - Optimized with Binary Search and Distinct emission
    private val currentTranscriptSegment = combine(
        playbackCoordinator.currentArtifact,
        playbackCoordinator.smoothPosition
    ) { artifact, position ->
        artifact?.transcript?.findSegmentAt(position.inWholeMilliseconds)
    }.distinctUntilChanged()

    val uiState: StateFlow<PlayerUiState> = combine(
        playbackState,
        metadata,
        uiControlState,
        reviewSessionManager.reviewProgress,
        currentTranscriptSegment
    ) { pb, md, ctrl, reviewState, transcriptSegment ->
        mapToUiState(pb, md, ctrl, reviewState, transcriptSegment)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PlayerUiState()
    )

    private fun mapToUiState(
        pb: PlaybackSubState,
        md: PlayerMetadata,
        ctrl: UiControlSubState,
        reviewState: ReviewState,
        transcriptSegment: TranscriptSegment?
    ): PlayerUiState {
        val artifact = pb.artifact
        val isOwner = artifact?.userId == authRepository.currentUserId
        
        // SYNC VALIDATION: Ensure metadata matches the current artifact to prevent transition flicker
        val isMetadataSynced = artifact != null && md.artifactId == artifact.id
        
        val progress = if (pb.duration > Duration.ZERO) (pb.position / pb.duration).toFloat() else 0f
        val furthestProgress = if (reviewState.artifactId == artifact?.id) reviewState.progress else 0f
        
        val mode = when {
            artifact == null -> PlayerMode.HIDDEN
            ctrl.isExpanded -> PlayerMode.FULLSCREEN
            else -> PlayerMode.MINI
        }

        return PlayerUiState(
            currentArtifact = artifact,
            isPlaying = pb.isPlaying,
            isBuffering = pb.isBuffering,
            currentPosition = pb.position.inWholeMilliseconds,
            durationMs = pb.duration.inWholeMilliseconds, // ExoPlayer duration is the source of truth
            playbackSpeed = pb.speed,
            isCommentUnlocked = if (isMetadataSynced) md.isCommentUnlocked else false,
            playbackProgress = progress,
            listeningProgress = furthestProgress,
            isExpanded = ctrl.isExpanded,
            playerMode = mode,
            isResonated = if (isMetadataSynced) md.isResonated else false,
            selectedReactionType = md.selectedReactionType,
            isResonating = if (isMetadataSynced) md.isResonating else false,
            isSaved = if (isMetadataSynced) md.isSaved else false,
            isOwner = isOwner,
            resonanceSummary = if (isMetadataSynced) md.resonanceSummary else "",
            commentCount = if (isMetadataSynced) md.commentCount else 0, // md.commentCount is live
            isSilenceSkipEnabled = pb.isSilenceSkipEnabled,
            sleepTimerMillisRemaining = ctrl.sleepTimerRemaining?.inWholeMilliseconds,
            currentTranscriptSegment = transcriptSegment,
            showAdvancedControls = ctrl.showAdvancedControls
        )
    }

    fun toggleResonate(type: ReactionType = metadata.value.selectedReactionType) {
        val artifact = uiState.value.currentArtifact ?: return
        val userId = authRepository.currentUser.value?.uid ?: return

        // REFACTOR: Optimistic state is now handled by ReactionRepository -> PendingInteractionDao -> UseCase
        viewModelScope.launch {
            reactionUseCase.toggleReaction(artifact.id, userId, type).onFailure { error ->
                _interactionError.emit("Could not resonate: ${error.message}")
            }
        }
    }

    fun toggleResonanceConnection() {
        val artifact = uiState.value.currentArtifact ?: return
        val currentUserId = authRepository.currentUser.value?.uid ?: return
        if (artifact.userId == currentUserId) return

        val wasResonating = metadata.value.isResonating
        
        // REFACTOR: Optimistic state handled by interaction DAO layer
        viewModelScope.launch {
            playerInteractionUseCase.toggleResonanceConnection(currentUserId, artifact.userId, wasResonating)
                .onFailure { error ->
                    _interactionError.emit("Resonance failed: ${error.message}")
                }
        }
    }

    fun toggleSave() {
        val artifact = uiState.value.currentArtifact ?: return

        viewModelScope.launch {
            try {
                playerInteractionUseCase.toggleSave(artifact)
            } catch (e: Exception) {
                _interactionError.emit("Could not preserve: ${e.message}")
            }
        }
    }

    fun playArtifact(artifact: Artifact, collection: List<Artifact> = emptyList()) {
        _isExpanded.value = true
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
        playbackCoordinator.seekTo(position.milliseconds)
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

        viewModelScope.launch {
            deleteArtifactUseCase.execute(artifact)
                .onSuccess {
                    playbackCoordinator.stop()
                    _isExpanded.value = false
                }.onFailure { e ->
                    _interactionError.emit("Unable to delete: ${e.message}")
                }
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

private data class UiControlSubState(
    val isExpanded: Boolean,
    val showAdvancedControls: Boolean,
    val sleepTimerRemaining: Duration?
)
