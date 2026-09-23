package com.saurabh.artifact.ui.publish.studio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackType
import com.saurabh.artifact.domain.IdentityScout
import com.saurabh.artifact.domain.PublishArtifactUseCase
import com.saurabh.artifact.domain.PublishingOrchestrator
import com.saurabh.artifact.security.DatabaseEncryptionManager
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.model.PlaybackSource
import com.saurabh.artifact.model.ProcessingStatus
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
    PUBLISHING(3),
    DELETING(4),
    SECURITY_REQUIRED(5);

    companion object {
        fun fromLifecycle(lifecycle: ArtifactLifecycle): StudioStep = when (lifecycle) {
            ArtifactLifecycle.PROCESSING -> PROCESSING
            ArtifactLifecycle.REVIEW_REQUIRED -> REVIEW
            ArtifactLifecycle.METADATA_REQUIRED -> DETAILS
            ArtifactLifecycle.READY_TO_PUBLISH -> APPROVAL
            ArtifactLifecycle.PUBLISHED -> PUBLISHING
            ArtifactLifecycle.DELETING -> DELETING
            ArtifactLifecycle.RECORDING,
            ArtifactLifecycle.DELETED -> REVIEW // Fallback for states not valid in Studio
        }
    }
}

data class StudioSessionState(
    val draftId: String? = null,
    val currentStep: StudioStep = StudioStep.REVIEW,
    val lifecycle: ArtifactLifecycle = ArtifactLifecycle.REVIEW_REQUIRED,
    
    // Feature Flags & Derived State
    val reviewSatisfied: Boolean = false,
    
    // DB-backed Completion Flags
    val reviewCompleted: Boolean = false,
    val titleCompleted: Boolean = false,
    val emotionCompleted: Boolean = false,
    val approvalCompleted: Boolean = false,
    
    // Metadata (DB-backed)
    val title: String = "",
    val emotions: List<Emotion> = emptyList(),
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
    val isRecovering: Boolean = false,
    val isProcessingActive: Boolean = false,
    val error: String? = null,
    val showPrivacyNudge: Boolean = false,
    val privacyWarnings: List<String> = emptyList(),
    val isRecoverySetup: Boolean = true
) {
    val effectiveEmotions: List<Emotion>
        get() = if (emotions.isNotEmpty()) emotions else if (emotion != null) listOf(emotion) else emptyList()
}

@HiltViewModel
class PublishingStudioViewModel @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val cleanupManager: com.saurabh.artifact.audio.ArtifactCleanupManager,
    private val playbackCoordinator: PlaybackCoordinator,
    private val publishArtifactUseCase: PublishArtifactUseCase,
    private val identityScout: IdentityScout,
    private val authRepository: AuthRepository,
    private val databaseEncryptionManager: DatabaseEncryptionManager,
    private val workManager: WorkManager,
    private val diagnosticLogger: DiagnosticLogger,
    private val publishingOrchestrator: PublishingOrchestrator
) : ViewModel() {

    private val _draftId = MutableStateFlow<String?>(null)
    
    // Phase 4: Temporary memory buffers for input fields
    private val _titleInput = MutableStateFlow<String?>(null)
    private var titleDebounceJob: kotlinx.coroutines.Job? = null

    // Phase 7: Transient UI navigation override
    private val _currentStepOverride = MutableStateFlow<StudioStep?>(null)

    // Local-only UI state
    private val _uiState = MutableStateFlow(StudioUiState())

    @OptIn(ExperimentalCoroutinesApi::class)
    val sessionState: StateFlow<StudioSessionState> = combine(
        _draftId.filterNotNull(),
        authRepository.currentUser
    ) { id, user -> id to user }
        .flatMapLatest { (id, user) -> 
            if (user == null) return@flatMapLatest flowOf(StudioSessionState())

            val draftFlow = recordingRepository.observeDraft(id).filterNotNull()
                .onEach { draft ->
                    if (draft.lifecycle == ArtifactLifecycle.PROCESSING) {
                        viewModelScope.launch {
                            publishingOrchestrator.ensureProcessingActive(draft.id)
                        }
                    }
                }
            val reviewFlow = playbackCoordinator.reviewProgress
            val recoveryFlow = recordingRepository.observeRecoveryState(id, workManager)
            val isRecoverySetupFlow = databaseEncryptionManager.isRecoverySetup
            
            // Combine local UI state
            val localContextFlow = combine(
                _titleInput,
                _uiState
            ) { title, ui -> title to ui }

            val persistentStateFlow = combine(
                draftFlow,
                reviewFlow,
                recoveryFlow,
                isRecoverySetupFlow,
                localContextFlow
            ) { draft, review, isRecovering, isRecoverySetup, localContext ->
                val (titleBuffer, ui) = localContext
                val effectiveEmotions = if (draft.emotions.isNotEmpty()) draft.emotions else if (draft.emotion != null) listOf(draft.emotion) else emptyList()
                
                StudioSessionState(
                    draftId = draft.id,
                    lifecycle = draft.lifecycle,
                    reviewSatisfied = draft.reviewCompleted,
                    reviewCompleted = draft.reviewCompleted,
                    titleCompleted = draft.titleCompleted,
                    emotionCompleted = draft.emotionCompleted,
                    approvalCompleted = draft.approvalCompleted,
                    title = titleBuffer ?: draft.title ?: "",
                    emotions = effectiveEmotions,
                    emotion = draft.emotion ?: effectiveEmotions.firstOrNull(),
                    isPlaying = playbackCoordinator.isPlaying.value,
                    playbackSpeed = playbackCoordinator.playbackSpeed.value,
                    currentPosition = if (draft.lifecycle == ArtifactLifecycle.REVIEW_REQUIRED) review.furthestPositionMs else 0L,
                    durationMs = draft.durationMs,
                    coveragePercent = if (draft.lifecycle == ArtifactLifecycle.REVIEW_REQUIRED) review.coveragePercent else draft.reviewProgress,
                    isPublishing = ui.isPublishing,
                    isSuccess = ui.isSuccess || draft.lifecycle == ArtifactLifecycle.PUBLISHED,
                    isQueuedOffline = ui.isQueuedOffline,
                    isRecovering = isRecovering,
                    isProcessingActive = draft.status.processing is ProcessingStatus.Active,
                    error = ui.error,
                    showPrivacyNudge = ui.showPrivacyNudge,
                    privacyWarnings = ui.privacyWarnings,
                    isRecoverySetup = isRecoverySetup
                )
            }

            combine(
                persistentStateFlow,
                _currentStepOverride
            ) { state, overrideStep ->
                val persistentStep = StudioStep.fromLifecycle(state.lifecycle)
                state.copy(currentStep = overrideStep ?: persistentStep)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StudioSessionState()
        )

    private var pendingPublishAfterRecovery = false

    init {
        diagnosticLogger.info(DiagnosticCategory.STUDIO, "VM_CREATED", mapOf("instanceId" to this.hashCode()))
        playbackCoordinator.stop()

        viewModelScope.launch {
            sessionState
                .map { it.isRecoverySetup }
                .distinctUntilChanged()
                .collect { isSetup ->
                    if (isSetup && pendingPublishAfterRecovery) {
                        pendingPublishAfterRecovery = false
                        if (_currentStepOverride.value == StudioStep.SECURITY_REQUIRED) {
                            _currentStepOverride.value = null
                        }
                        performPublish()
                    }
                }
        }
    }

    fun loadDraft(draftId: String) {
        if (_draftId.value == draftId) return
        diagnosticLogger.info(DiagnosticCategory.STUDIO, "DRAFT_LOADED", mapOf(LogKeys.DRAFT_ID to draftId))
        _draftId.value = draftId
        
        // Reset buffers when loading new draft
        _titleInput.value = null
        _currentStepOverride.value = null
        pendingPublishAfterRecovery = false

        viewModelScope.launch {
            playbackCoordinator.playDraftPreview(draftId, source = PlaybackSource.REVIEW_DRAFT)
        }
    }

    fun deleteDraft() {
        val draftId = _draftId.value ?: return
        diagnosticLogger.info(DiagnosticCategory.STUDIO, "DRAFT_DELETE_REQUESTED", mapOf(LogKeys.DRAFT_ID to draftId))
        viewModelScope.launch {
            // CleanupManager is currently user-agnostic for physical purge, 
            // but the triggering metadata update in deleteDraft uses user-scoped DAO methods internally
            cleanupManager.deleteDraft(draftId)
        }
    }

    /**
     * Updates the draft title using a temporary UI buffer for responsiveness,
     * maintaining the [Publishing Flow Invariants](file:///docs/architecture/PublishingFlowInvariants.md).
     */
    fun updateTitle(title: String) {
        val draftId = _draftId.value ?: return
        val userId = authRepository.currentUserId
        if (userId.isEmpty()) return
        
        // Enforce 70-character limit at the boundary
        val constrainedTitle = title.take(70)
        
        // Update local buffer immediately for zero-latency UI
        _titleInput.value = constrainedTitle

        // Phase 4: Debounce Room writes
        titleDebounceJob?.cancel()
        titleDebounceJob = viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            
            diagnosticLogger.debug(DiagnosticCategory.STUDIO, "TITLE_UPDATE_DEBOUNCED", mapOf(LogKeys.DRAFT_ID to draftId, "titleLength" to constrainedTitle.length))
            val result = recordingRepository.updateDraftMetadata(draftId, constrainedTitle, sessionState.value.effectiveEmotions)
            if (result.isSuccess) {
                recordingRepository.updateStudioState(
                    id = draftId,
                    title = constrainedTitle.isNotBlank(),
                    emotion = sessionState.value.emotionCompleted,
                    approval = sessionState.value.approvalCompleted
                )
                // Clear buffer ONLY after successful persistence to let Room take authority back
                _titleInput.value = null
            }
        }
    }

    fun updateEmotion(emotion: Emotion) {
        updateEmotions(listOf(emotion))
    }

    fun updateEmotions(emotions: List<Emotion>) {
        val draftId = _draftId.value ?: return
        val userId = authRepository.currentUserId
        if (userId.isEmpty()) return

        val limitedEmotions = emotions.take(3)
        diagnosticLogger.debug(DiagnosticCategory.STUDIO, "EMOTIONS_UPDATED", mapOf(LogKeys.DRAFT_ID to draftId, "count" to limitedEmotions.size))
        viewModelScope.launch {
            recordingRepository.updateDraftMetadata(draftId, sessionState.value.title, limitedEmotions)
            recordingRepository.updateStudioState(
                id = draftId,
                title = sessionState.value.titleCompleted,
                emotion = limitedEmotions.isNotEmpty(),
                approval = sessionState.value.approvalCompleted
            )
        }
    }

    fun nextStep() {
        val draftId = _draftId.value ?: return
        val currentState = sessionState.value
        val currentStep = currentState.currentStep
        val currentLifecycle = currentState.lifecycle
        
        // Determine the logically next step from UI perspective
        val nextStep = when (currentStep) {
            StudioStep.REVIEW -> StudioStep.DETAILS
            StudioStep.DETAILS -> StudioStep.APPROVAL
            StudioStep.APPROVAL -> StudioStep.APPROVAL // Handled by onPublishClick
            else -> currentStep
        }

        // Determine persistent step derived from DB
        val persistentStep = StudioStep.fromLifecycle(currentLifecycle)

        diagnosticLogger.info(DiagnosticCategory.STUDIO, "NEXT_STEP_REQUESTED", mapOf(
            LogKeys.DRAFT_ID to draftId,
            "fromStep" to currentStep.name,
            "toStep" to nextStep.name,
            "persistentStep" to persistentStep.name
        ))

        if (currentStep == StudioStep.REVIEW && playbackCoordinator.isPlaying.value) {
            playbackCoordinator.togglePlayPause()
        }

        if (nextStep.index <= persistentStep.index) {
            // Pure UI navigation to a step we've already reached or bypassed
            _currentStepOverride.value = if (nextStep == persistentStep) null else nextStep
        } else {
            // Advancing persistent state
            val nextLifecycle = when (currentLifecycle) {
                ArtifactLifecycle.REVIEW_REQUIRED -> ArtifactLifecycle.METADATA_REQUIRED
                ArtifactLifecycle.METADATA_REQUIRED -> ArtifactLifecycle.READY_TO_PUBLISH
                else -> currentLifecycle
            }

            val userId = authRepository.currentUserId
            if (userId.isEmpty()) return

            viewModelScope.launch {
                val result = recordingRepository.updateLifecycle(draftId, nextLifecycle)
                if (result.isSuccess) {
                    // Once persistent state catches up, we can clear the override
                    _currentStepOverride.value = null
                } else {
                    // Fallback to UI override if DB update fails for some reason (e.g. offline validation)
                    _currentStepOverride.value = nextStep
                }
            }
        }
    }

    fun previousStep() {
        val draftId = _draftId.value ?: return
        val currentState = sessionState.value
        val currentStep = currentState.currentStep

        if (currentStep == StudioStep.SECURITY_REQUIRED) {
            _currentStepOverride.value = null
            pendingPublishAfterRecovery = false
            return
        }
        
        val prevStep = when (currentStep) {
            StudioStep.DETAILS -> StudioStep.REVIEW
            StudioStep.APPROVAL -> StudioStep.DETAILS
            StudioStep.PUBLISHING -> if (currentState.error != null) StudioStep.APPROVAL else currentStep
            else -> currentStep
        }
        
        if (prevStep != currentStep) {
            diagnosticLogger.info(DiagnosticCategory.STUDIO, "PREVIOUS_STEP_REQUESTED", mapOf(
                LogKeys.DRAFT_ID to draftId, 
                "fromStep" to currentStep.name,
                "toStep" to prevStep.name
            ))

            if (currentStep == StudioStep.PUBLISHING) {
                // Reset publishing error state when retreating
                _uiState.update { it.copy(error = null, isPublishing = false) }
            }

            // Pure UI override - NEVER persist backward transitions
            val persistentStep = StudioStep.fromLifecycle(currentState.lifecycle)
            _currentStepOverride.value = if (prevStep == persistentStep) null else prevStep
        }
    }

    fun onPublishClick() {
        diagnosticLogger.info(DiagnosticCategory.PUBLISH, "PUBLISH_CLICKED")
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
        
        // SECURITY ENFORCEMENT: Requirement from Phase 3 Chapter 25
        if (!state.isRecoverySetup) {
            diagnosticLogger.info(DiagnosticCategory.SECURITY, "PUBLISH_REDIRECT_TO_MNEMONIC", mapOf(LogKeys.DRAFT_ID to draftId))
            pendingPublishAfterRecovery = true
            _currentStepOverride.value = StudioStep.SECURITY_REQUIRED
            return
        }

        if (_currentStepOverride.value == StudioStep.SECURITY_REQUIRED) {
            _currentStepOverride.value = null
        }
        pendingPublishAfterRecovery = false

        // Use reviewSatisfied to allow publishing when bypass is active
        if (state.title.isBlank() || state.effectiveEmotions.isEmpty() || !state.reviewSatisfied) {
            diagnosticLogger.warn(DiagnosticCategory.PUBLISH, "PUBLISH_PRECONDITION_FAILED", mapOf(
                "titleEmpty" to state.title.isBlank(),
                "emotionsMissing" to state.effectiveEmotions.isEmpty(),
                "reviewNotSatisfied" to !state.reviewSatisfied
            ))
            return
        }

        diagnosticLogger.info(DiagnosticCategory.PUBLISH, "PUBLISH_PERFORM_STARTED", mapOf(LogKeys.DRAFT_ID to draftId))
        val userId = authRepository.currentUserId
        if (userId.isEmpty()) return

        // Transition to Publishing step immediately to provide feedback
        _currentStepOverride.value = StudioStep.PUBLISHING

        viewModelScope.launch {
            _uiState.update { it.copy(isPublishing = true) }
            
            recordingRepository.getDraft(draftId).onSuccess { draft ->
                publishArtifactUseCase(draft.id)
                    .onSuccess { result ->
                        diagnosticLogger.info(DiagnosticCategory.PUBLISH, "PUBLISH_INITIATION_SUCCESS", mapOf(LogKeys.DRAFT_ID to draftId))
                        playbackCoordinator.stop()

                        if (result == PublishingResult.FAILED) {
                            _uiState.update { it.copy(isPublishing = false, error = "Publishing failed to initiate. Please try again.") }
                        } else {
                            _uiState.update { 
                                it.copy(
                                    isPublishing = false, 
                                    isSuccess = true,
                                    isQueuedOffline = result == PublishingResult.QUEUED_OFFLINE
                                ) 
                            }
                        }
                    }
                    .onFailure { e ->
                        diagnosticLogger.error(DiagnosticCategory.PUBLISH, "PUBLISH_INITIATION_ERROR", mapOf(LogKeys.DRAFT_ID to draftId, LogKeys.EXCEPTION_MESSAGE to (e.message ?: "null")), e)
                        _uiState.update { 
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
        diagnosticLogger.info(DiagnosticCategory.STUDIO, "VM_DESTROYED", mapOf("instanceId" to this.hashCode()))
        super.onCleared()
        playbackCoordinator.requestStop(PlaybackType.DRAFT_PREVIEW)
    }
}

data class StudioUiState(
    val isPublishing: Boolean = false,
    val isSuccess: Boolean = false,
    val isQueuedOffline: Boolean = false,
    val isRecovering: Boolean = false,
    val error: String? = null,
    val showPrivacyNudge: Boolean = false,
    val privacyWarnings: List<String> = emptyList(),
    val isRecoverySetup: Boolean = true
)
