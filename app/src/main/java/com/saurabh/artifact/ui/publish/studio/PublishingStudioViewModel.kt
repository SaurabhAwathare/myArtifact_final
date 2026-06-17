package com.saurabh.artifact.ui.publish.studio

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackType
import com.saurabh.artifact.domain.IdentityScout
import com.saurabh.artifact.domain.PublishArtifactUseCase
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.model.PublishingResult
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.util.SecureString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class StudioStep(val index: Int) {
    PROCESSING(-1),
    REVIEW(0),
    DETAILS(1),
    APPROVAL(2),
    PUBLISHING(3);

    companion object {
        fun fromLifecycle(lifecycle: ArtifactLifecycle): StudioStep = when (lifecycle) {
            ArtifactLifecycle.PROCESSING -> PROCESSING
            ArtifactLifecycle.REVIEW_REQUIRED -> REVIEW
            ArtifactLifecycle.METADATA_REQUIRED -> DETAILS
            ArtifactLifecycle.READY_TO_PUBLISH -> APPROVAL
            ArtifactLifecycle.PUBLISHED -> PUBLISHING
            else -> REVIEW
        }
    }
}

data class StudioSessionState(
    val draftId: String? = null,
    val currentStep: StudioStep = StudioStep.REVIEW,
    val lifecycle: ArtifactLifecycle = ArtifactLifecycle.REVIEW_REQUIRED,
    
    // DB-backed Completion Flags
    val reviewCompleted: Boolean = false,
    val titleCompleted: Boolean = false,
    val emotionCompleted: Boolean = false,
    val approvalCompleted: Boolean = false,
    
    // Metadata (DB-backed)
    val title: String = "",
    val emotion: Emotion? = null,
    
    // Playback State (Local UI)
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val currentPosition: Long = 0L,
    val durationMs: Long = 0L,
    val coveragePercent: Float = 0f,
    
    // Publication State (Local UI)
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

    private val _draftId = MutableStateFlow<String?>(null)
    
    // Local-only UI state
    private val _uiState = MutableStateFlow(StudioUiState())

    @OptIn(ExperimentalCoroutinesApi::class)
    val sessionState: StateFlow<StudioSessionState> = _draftId
        .filterNotNull()
        .flatMapLatest { id -> 
            Log.d("VM_TRACE", "[VM_TRACE] flatMapLatest: draftId=$id | Instance=${this.hashCode()}")
            recordingRepository.observeDraft(id) 
        }
        .filterNotNull()
        .combine(playbackCoordinator.reviewProgress) { draft, reviewState ->
            draft to reviewState
        }
        .combine(_uiState) { (draft, review), ui ->
            val step = StudioStep.fromLifecycle(draft.lifecycle)
            
            when (step) {
                StudioStep.REVIEW -> Log.d("NAV_TRACE", "Navigate -> Review")
                StudioStep.DETAILS -> Log.d("NAV_TRACE", "Navigate -> Metadata")
                else -> {}
            }

            Log.d("STATE_TRACE", "[STATE_TRACE] sessionState EMIT: draftId=${draft.id}, lifecycle=${draft.lifecycle}, step=$step, reviewCompleted=${draft.reviewCompleted} | Instance=${this.hashCode()}")
            StudioSessionState(
                draftId = draft.id,
                currentStep = step,
                lifecycle = draft.lifecycle,
                reviewCompleted = draft.reviewCompleted,
                titleCompleted = draft.titleCompleted,
                emotionCompleted = draft.emotionCompleted,
                approvalCompleted = draft.approvalCompleted,
                title = draft.title ?: "",
                emotion = draft.emotion,
                
                // Playback info
                isPlaying = playbackCoordinator.isPlaying.value,
                playbackSpeed = playbackCoordinator.playbackSpeed.value,
                currentPosition = if (draft.lifecycle == ArtifactLifecycle.REVIEW_REQUIRED) review.furthestPositionMs else 0L,
                durationMs = draft.durationMs,
                coveragePercent = if (draft.lifecycle == ArtifactLifecycle.REVIEW_REQUIRED) review.coveragePercent else draft.reviewProgress,
                
                // UI info
                isPublishing = ui.isPublishing,
                isSuccess = ui.isSuccess || draft.lifecycle == ArtifactLifecycle.PUBLISHED,
                isQueuedOffline = ui.isQueuedOffline,
                error = ui.error,
                showPrivacyNudge = ui.showPrivacyNudge,
                privacyWarnings = ui.privacyWarnings
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StudioSessionState()
        )

    init {
        Log.d("VM_TRACE", "[VM_TRACE] CREATED: Instance=${this.hashCode()}")
        playbackCoordinator.stop()

        // Handle playback completion for automatic state persistence
        viewModelScope.launch {
            playbackCoordinator.playbackCompletedEvent.collect { completedId ->
                if (completedId == _draftId.value) {
                    Log.d("STUDIO_TRACE", "Playback completed for $completedId. Updating DB.")
                    recordingRepository.updateStudioState(
                        id = completedId,
                        review = true,
                        title = sessionState.value.titleCompleted,
                        emotion = sessionState.value.emotionCompleted,
                        approval = sessionState.value.approvalCompleted
                    )
                }
            }
        }
    }

    fun loadDraft(draftId: String) {
        if (_draftId.value == draftId) return
        Log.d("VM_TRACE", "[VM_TRACE] loadDraft: draftId=$draftId | Instance=${this.hashCode()}")
        _draftId.value = draftId
        
        viewModelScope.launch {
            playbackCoordinator.playDraftPreview(draftId)
        }
    }

    fun updateTitle(title: String) {
        val draftId = _draftId.value ?: return
        Log.d("STATE_TRACE", "[STATE_TRACE] updateTitle: title='$title', draftId=$draftId | Instance=${this.hashCode()}")
        viewModelScope.launch {
            recordingRepository.updateDraftMetadata(draftId, title, sessionState.value.emotion)
            recordingRepository.updateStudioState(
                id = draftId,
                review = sessionState.value.reviewCompleted,
                title = title.isNotBlank(),
                emotion = sessionState.value.emotionCompleted,
                approval = sessionState.value.approvalCompleted
            )
        }
    }

    fun updateEmotion(emotion: Emotion) {
        val draftId = _draftId.value ?: return
        Log.d("STATE_TRACE", "[STATE_TRACE] updateEmotion: emotion=${emotion.label}, draftId=$draftId | Instance=${this.hashCode()}")
        viewModelScope.launch {
            recordingRepository.updateDraftMetadata(draftId, sessionState.value.title, emotion)
            recordingRepository.updateStudioState(
                id = draftId,
                review = sessionState.value.reviewCompleted,
                title = sessionState.value.titleCompleted,
                emotion = true,
                approval = sessionState.value.approvalCompleted
            )
        }
    }

    fun nextStep() {
        val draftId = _draftId.value ?: return
        val currentLifecycle = sessionState.value.lifecycle
        
        val nextLifecycle = when (currentLifecycle) {
            ArtifactLifecycle.REVIEW_REQUIRED -> ArtifactLifecycle.METADATA_REQUIRED
            ArtifactLifecycle.METADATA_REQUIRED -> ArtifactLifecycle.READY_TO_PUBLISH
            ArtifactLifecycle.READY_TO_PUBLISH -> ArtifactLifecycle.READY_TO_PUBLISH // Handled by performPublish
            else -> currentLifecycle
        }

        Log.d("STATE_TRACE", "[STATE_TRACE] nextStep: draftId=$draftId, currentStep=${sessionState.value.currentStep}, nextStep=${StudioStep.fromLifecycle(nextLifecycle)}, source=nextStep | Instance=${this.hashCode()}")
        
        if (currentLifecycle == ArtifactLifecycle.REVIEW_REQUIRED && playbackCoordinator.isPlaying.value) {
            playbackCoordinator.togglePlayPause()
        }

        viewModelScope.launch {
            recordingRepository.updateLifecycle(draftId, nextLifecycle)
        }
    }

    fun previousStep() {
        val draftId = _draftId.value ?: return
        val currentLifecycle = sessionState.value.lifecycle
        
        val prevLifecycle = when (currentLifecycle) {
            ArtifactLifecycle.METADATA_REQUIRED -> ArtifactLifecycle.REVIEW_REQUIRED
            ArtifactLifecycle.READY_TO_PUBLISH -> ArtifactLifecycle.METADATA_REQUIRED
            else -> currentLifecycle
        }
        
        Log.d("STATE_TRACE", "[STATE_TRACE] previousStep: draftId=$draftId, currentStep=${sessionState.value.currentStep}, prevStep=${StudioStep.fromLifecycle(prevLifecycle)}, source=previousStep | Instance=${this.hashCode()}")

        viewModelScope.launch {
            recordingRepository.updateLifecycle(draftId, prevLifecycle)
        }
    }

    fun onPublishClick() {
        Log.d("STUDIO_TRACE", "Publish button pressed")
        val state = sessionState.value
        val title = state.title
        
        viewModelScope.launch {
            val user = authRepository.currentUser.first()
            val realName = user?.displayName?.let { SecureString.fromString(it) }
            val email = user?.email?.let { SecureString.fromString(it) }

            val warnings = identityScout.detectLeaks(title, realName, email)
            
            realName?.clear()
            email?.clear()

            if (warnings.isNotEmpty()) {
                _uiState.update { it.copy(
                    showPrivacyNudge = true,
                    privacyWarnings = warnings.map { w -> w.message }
                ) }
            } else {
                performPublish()
            }
        }
    }

    fun dismissPrivacyNudge() {
        _uiState.update { it.copy(showPrivacyNudge = false) }
    }

    fun confirmPublishAnyway() {
        _uiState.update { it.copy(showPrivacyNudge = false) }
        performPublish()
    }

    private fun performPublish() {
        val state = sessionState.value
        val draftId = state.draftId ?: return
        if (state.title.isBlank() || state.emotion == null || !state.reviewCompleted) return

        Log.d("STATE_TRACE", "[STATE_TRACE] performPublish: draftId=$draftId | Instance=${this.hashCode()}")
        viewModelScope.launch {
            _uiState.update { 
                Log.d("STATE_TRACE", "[STATE_TRACE] performPublish: _uiState.update isPublishing=true")
                it.copy(isPublishing = true) 
            }
            
            recordingRepository.getDraft(draftId).onSuccess { draft ->
                publishArtifactUseCase(draft.localAudioPath)
                    .onSuccess { result ->
                        if (result == PublishingResult.FAILED) {
                            _uiState.update { 
                                Log.e("STATE_TRACE", "[STATE_TRACE] performPublish: FAILED result from use case")
                                it.copy(isPublishing = false, error = "Publishing failed to initiate. Please try again.") 
                            }
                            return@onSuccess
                        }

                        Log.d("LOOP_FIX", "Publishing success -> stop() called")
                        playbackCoordinator.stop()

                        _uiState.update { 
                            Log.d("STATE_TRACE", "[STATE_TRACE] performPublish: _uiState.update isSuccess=true")
                            it.copy(
                                isPublishing = false, 
                                isSuccess = true,
                                isQueuedOffline = result == PublishingResult.QUEUED_OFFLINE
                            ) 
                        }
                    }
                    .onFailure { e ->
                        _uiState.update { 
                            Log.d("STATE_TRACE", "[STATE_TRACE] performPublish: _uiState.update error=${e.message}")
                            it.copy(isPublishing = false, error = e.message) 
                        }
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
        Log.d("VM_TRACE", "[VM_TRACE] DESTROYED: Instance=${this.hashCode()}")
        super.onCleared()
        playbackCoordinator.requestStop(PlaybackType.DRAFT_PREVIEW)
    }
}

data class StudioUiState(
    val isPublishing: Boolean = false,
    val isSuccess: Boolean = false,
    val isQueuedOffline: Boolean = false,
    val error: String? = null,
    val showPrivacyNudge: Boolean = false,
    val privacyWarnings: List<String> = emptyList()
)
