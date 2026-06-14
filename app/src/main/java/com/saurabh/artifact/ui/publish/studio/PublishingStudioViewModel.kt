package com.saurabh.artifact.ui.publish.studio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackType
import com.saurabh.artifact.domain.IdentityScout
import com.saurabh.artifact.domain.PublishArtifactUseCase
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.model.PublishingResult
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.util.SecureString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class StudioStep(val index: Int) {
    REVIEW(0),
    DETAILS(1),
    APPROVAL(2),
    PUBLISHING(3)
}

data class StudioSessionState(
    val draftId: String? = null,
    val currentStep: StudioStep = StudioStep.REVIEW,
    val reviewCompleted: Boolean = false,
    val titleCompleted: Boolean = false,
    val emotionCompleted: Boolean = false,
    val approvalCompleted: Boolean = false,
    
    // Metadata
    val title: String = "",
    val emotion: Emotion? = null,
    
    // Playback State
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val currentPosition: Long = 0L,
    val durationMs: Long = 0L,
    
    // Progress
    val coveragePercent: Float = 0f,
    val isPlaybackEnded: Boolean = false,
    
    // Publication State
    val isPublishing: Boolean = false,
    val isSuccess: Boolean = false,
    val isQueuedOffline: Boolean = false,
    val error: String? = null,
    val showPrivacyNudge: Boolean = false,
    val privacyWarnings: List<String> = emptyList()
)

@HiltViewModel
class PublishingStudioViewModel @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val playbackCoordinator: PlaybackCoordinator,
    private val publishArtifactUseCase: PublishArtifactUseCase,
    private val identityScout: IdentityScout,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _sessionState = MutableStateFlow(StudioSessionState())
    val sessionState = _sessionState.asStateFlow()

    init {
        // Stop any existing global playback when entering the studio
        playbackCoordinator.stop()
        
        // Observe Playback State and calculate playhead-driven progress
        viewModelScope.launch {
            playbackCoordinator.reviewProgress.collect { reviewState ->
                _sessionState.update { state ->
                    state.copy(
                        isPlaying = playbackCoordinator.isPlaying.value,
                        playbackSpeed = playbackCoordinator.playbackSpeed.value,
                        currentPosition = reviewState.furthestPositionMs,
                        durationMs = reviewState.durationMs,
                        coveragePercent = reviewState.coveragePercent,
                        reviewCompleted = reviewState.isThresholdMet
                    )
                }
            }
        }

        // Explicitly handle STATE_ENDED for 100% completion
        viewModelScope.launch {
            playbackCoordinator.playbackCompletedEvent.collect { completedId ->
                if (completedId == _sessionState.value.draftId) {
                    _sessionState.update { it.copy(reviewCompleted = true, coveragePercent = 1f) }
                    saveCompletionState()
                }
            }
        }
    }

    fun loadDraft(draftId: String) {
        viewModelScope.launch {
            recordingRepository.observeDraft(draftId).firstOrNull()?.let { draft ->
                _sessionState.update { state ->
                    state.copy(
                        draftId = draftId,
                        title = state.title.ifBlank { draft.title ?: "" },
                        emotion = state.emotion ?: draft.emotion,
                        currentStep = try { StudioStep.valueOf(draft.studioStep) } catch (_: Exception) { StudioStep.REVIEW },
                        reviewCompleted = draft.reviewCompleted,
                        titleCompleted = draft.titleCompleted,
                        emotionCompleted = draft.emotionCompleted,
                        approvalCompleted = draft.approvalCompleted,
                        coveragePercent = draft.reviewProgress,
                        durationMs = draft.durationMs // Ensure duration is set from draft on load
                    )
                }
                
                // Start playback in studio mode
                playbackCoordinator.playDraftPreview(draftId)
            }
        }
    }

    fun updateTitle(title: String) {
        _sessionState.update { it.copy(
            title = title,
            titleCompleted = title.isNotBlank()
        ) }
        autoSaveMetadata()
    }

    fun updateEmotion(emotion: Emotion) {
        _sessionState.update { it.copy(
            emotion = emotion,
            emotionCompleted = true
        ) }
        autoSaveMetadata()
    }

    fun nextStep() {
        val next = when (_sessionState.value.currentStep) {
            StudioStep.REVIEW -> StudioStep.DETAILS
            StudioStep.DETAILS -> StudioStep.APPROVAL
            StudioStep.APPROVAL -> StudioStep.PUBLISHING
            StudioStep.PUBLISHING -> StudioStep.PUBLISHING
        }
        _sessionState.update { it.copy(currentStep = next) }
        saveCompletionState()
    }

    fun previousStep() {
        val prev = when (_sessionState.value.currentStep) {
            StudioStep.REVIEW -> StudioStep.REVIEW
            StudioStep.DETAILS -> StudioStep.REVIEW
            StudioStep.APPROVAL -> StudioStep.DETAILS
            StudioStep.PUBLISHING -> StudioStep.APPROVAL
        }
        _sessionState.update { it.copy(currentStep = prev) }
    }

    private fun autoSaveMetadata() {
        val state = _sessionState.value
        val draftId = state.draftId ?: return
        viewModelScope.launch {
            recordingRepository.updateDraftMetadata(
                id = draftId,
                title = state.title,
                emotion = state.emotion
            )
        }
    }

    private fun saveCompletionState() {
        val state = _sessionState.value
        val draftId = state.draftId ?: return
        viewModelScope.launch {
            recordingRepository.updateStudioState(
                id = draftId,
                step = state.currentStep.name,
                review = state.reviewCompleted,
                title = state.titleCompleted,
                emotion = state.emotionCompleted,
                approval = state.approvalCompleted
            )
        }
    }

    fun onPublishClick() {
        val state = _sessionState.value
        val title = state.title
        
        viewModelScope.launch {
            val user = authRepository.currentUser.first()
            val realName = user?.displayName?.let { SecureString.fromString(it) }
            val email = user?.email?.let { SecureString.fromString(it) }

            val warnings = identityScout.detectLeaks(title, realName, email)
            
            realName?.clear()
            email?.clear()

            if (warnings.isNotEmpty()) {
                _sessionState.update { it.copy(
                    showPrivacyNudge = true,
                    privacyWarnings = warnings.map { w -> w.message }
                ) }
            } else {
                performPublish()
            }
        }
    }

    fun dismissPrivacyNudge() {
        _sessionState.update { it.copy(showPrivacyNudge = false) }
    }

    fun confirmPublishAnyway() {
        _sessionState.update { it.copy(showPrivacyNudge = false) }
        performPublish()
    }

    private fun performPublish() {
        val state = _sessionState.value
        val draftId = state.draftId ?: return
        if (state.title.isBlank() || state.emotion == null || !state.reviewCompleted) return

        viewModelScope.launch {
            _sessionState.update { it.copy(isPublishing = true, currentStep = StudioStep.PUBLISHING) }
            
            recordingRepository.getDraft(draftId).onSuccess { draft ->
                publishArtifactUseCase(draft.localAudioPath)
                    .onSuccess { result ->
                        _sessionState.update { it.copy(
                            isPublishing = false, 
                            isSuccess = true,
                            isQueuedOffline = result == PublishingResult.QUEUED_OFFLINE
                        ) }
                    }
                    .onFailure { e ->
                        _sessionState.update { it.copy(isPublishing = false, error = e.message) }
                    }
            }
        }
    }

    fun togglePlayback() {
        playbackCoordinator.togglePlayPause()
    }

    fun seekTo(progress: Float) {
        viewModelScope.launch {
            val dur = playbackCoordinator.duration.first()
            playbackCoordinator.seekTo(dur * progress.toDouble())
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackCoordinator.setPlaybackSpeed(speed)
    }

    override fun onCleared() {
        super.onCleared()
        playbackCoordinator.requestStop(PlaybackType.DRAFT_PREVIEW)
    }
}
