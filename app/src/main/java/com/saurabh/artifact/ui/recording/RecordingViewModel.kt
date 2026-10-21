package com.saurabh.artifact.ui.recording

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.data.local.RecordingStatus
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.model.PromptSelectionResult
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.PromptRepository
import com.saurabh.artifact.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecordingViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val artifactRepository: ArtifactRepository,
    private val promptRepository: PromptRepository,
    private val promptManager: com.saurabh.artifact.domain.prompt.ReflectionPromptManager,
    private val userSessionManager: UserSessionManager,
    private val recordingSessionManager: RecordingSessionManager,
    private val savedStateHandle: SavedStateHandle,
    private val diagnosticLogger: DiagnosticLogger,
) : ViewModel() {

    private var currentSessionBatch: Int = 1

    private var sessionUserId: String? = null

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private val _events = Channel<RecordingEvent>(capacity = Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _navigationEvents = Channel<String>(capacity = Channel.BUFFERED)
    val navigationEvents = _navigationEvents.receiveAsFlow()

    init {
        val currentUser = authRepository.currentUser.value
        if (currentUser == null) {
            diagnosticLogger.warn(DiagnosticCategory.AUTH, "RECORDING_BLOCKED_AUTH", mapOf("reason" to "USER_NULL"))
        } else {
            sessionUserId = currentUser.uid
            loadInitialPrompt()
            observeRecordingSession()
            observeEpisodePreview(currentUser.uid)
            
            // Immediate start for InstantRecord flow
            _uiState.update { it.copy(flowState = RecordingFlowState.RECORDING) }
            
            viewModelScope.launch {
                _events.send(RecordingEvent.RequestStart)
            }
        }
    }

    private fun observeEpisodePreview(userId: String) {
        combine(
            artifactRepository.getMaxPublishedEpisodeNumber(userId),
            userRepository.streamUserProfile(userId)
        ) { maxLocalEpisode, userProfile ->
            val maxLocal = maxLocalEpisode ?: 0L
            val count = userProfile?.artifactsCount ?: 0L
            maxOf(maxLocal, count) + 1L
        }
        .onEach { preview ->
            _uiState.update { it.copy(nextEpisodeNumberPreview = preview) }
        }
        .catch { e ->
            diagnosticLogger.error(DiagnosticCategory.RECORDING, "EPISODE_PREVIEW_CALCULATION_FAILED", mapOf("userId" to userId), e)
        }
        .launchIn(viewModelScope)
    }

    private fun loadInitialPrompt() {
        viewModelScope.launch {
            promptRepository.initializeIfEmpty()

            val currentUser = authRepository.currentUser.value
            val currentUid = currentUser?.uid
            if (!currentUid.isNullOrBlank()) {
                sessionUserId = currentUid
            }

            // Priority 1: Navigation Argument (Must be a valid approved prompt ID)
            val navPromptId = savedStateHandle.get<String>("promptId")?.takeIf { it.isNotBlank() }
            if (navPromptId != null) {
                val approvedPrompt = promptRepository.getApprovedPromptById(navPromptId)
                if (approvedPrompt != null) {
                    _uiState.update { it.copy(currentPrompt = approvedPrompt, isPromptVisible = true) }
                    currentSessionBatch = approvedPrompt.batchNumber
                    userSessionManager.setActivePromptId(approvedPrompt.id)
                    return@launch
                }
            }

            // Priority 2: Session-active prompt (if valid approved & unconsumed for current user)
            val activePromptId = userSessionManager.activePromptId.firstOrNull()
            if (activePromptId != null && !currentUid.isNullOrBlank()) {
                val activePrompt = promptRepository.getApprovedPromptById(activePromptId)
                if (activePrompt != null) {
                    val isConsumed = promptManager.isPromptConsumed(currentUid, activePromptId)
                    if (!isConsumed) {
                        _uiState.update { it.copy(currentPrompt = activePrompt, isPromptVisible = true) }
                        currentSessionBatch = activePrompt.batchNumber
                        return@launch
                    }
                }
            }

            // Priority 3: Fresh account-scoped prompt selection starting from Batch 1
            if (currentUid.isNullOrBlank()) {
                diagnosticLogger.warn(DiagnosticCategory.RECORDING, "PROMPT_LOAD_BLOCKED_UNAUTHENTICATED")
                _uiState.update { it.copy(currentPrompt = null, isPromptVisible = false) }
                userSessionManager.setActivePromptId(null)
                return@launch
            }

            when (val result = promptManager.getNextPromptForUser(userId = currentUid, startBatchNumber = 1)) {
                is PromptSelectionResult.Success -> {
                    _uiState.update { it.copy(currentPrompt = result.prompt, isPromptVisible = true) }
                    currentSessionBatch = result.batchNumber
                    userSessionManager.setActivePromptId(result.prompt.id)
                }
                is PromptSelectionResult.CatalogExhausted -> {
                    diagnosticLogger.info(DiagnosticCategory.RECORDING, "PROMPT_CATALOG_EXHAUSTED", mapOf("userId" to currentUid))
                    _uiState.update { it.copy(currentPrompt = null, isPromptVisible = false) }
                    userSessionManager.setActivePromptId(null)
                }
                is PromptSelectionResult.BatchExhausted -> {
                    _uiState.update { it.copy(currentPrompt = null, isPromptVisible = false) }
                    userSessionManager.setActivePromptId(null)
                }
                is PromptSelectionResult.Error -> {
                    diagnosticLogger.error(
                        DiagnosticCategory.RECORDING,
                        "PROMPT_LOADING_FAILED",
                        mapOf("userId" to currentUid),
                        result.cause
                    )
                    _uiState.update { it.copy(currentPrompt = null, isPromptVisible = false) }
                    userSessionManager.setActivePromptId(null)
                }
            }
        }
    }

    private fun observeRecordingSession() {
        recordingSessionManager.sessionState
            .onEach { state ->
                val error = when (state.errorCode) {
                    "PERMISSION_DENIED" -> RecordingError.PermissionDenied
                    "HARDWARE_IN_USE" -> RecordingError.HardwareInUse
                    "STORAGE_FULL" -> RecordingError.StorageFull
                    "RESERVATION_FAILED" -> RecordingError.ReservationFailed
                    null -> if (state.status == RecordingStatus.FAILED) RecordingError.Unknown else null
                    else -> RecordingError.Unknown
                }

                _uiState.update { current ->
                    val isServiceResetAfterFailure = state.status == RecordingStatus.IDLE &&
                            current.status == RecordingStatus.FAILED &&
                            current.error != null

                    val resolvedStatus = if (isServiceResetAfterFailure) RecordingStatus.FAILED else state.status
                    val resolvedError = if (isServiceResetAfterFailure) current.error else error

                    val isStoppedOrFailed = resolvedStatus == RecordingStatus.FAILED || resolvedStatus == RecordingStatus.IDLE
                    val resolvedAmplitudes = if (isStoppedOrFailed) emptyList() else state.amplitudes
                    val resolvedAmplitude = if (isStoppedOrFailed) 0f else current.currentAmplitude

                    current.copy(
                        status = resolvedStatus,
                        error = resolvedError,
                        durationSeconds = state.durationSeconds,
                        currentOutputFile = state.outputFile?.absolutePath ?: current.currentOutputFile,
                        amplitudes = resolvedAmplitudes,
                        currentAmplitude = resolvedAmplitude,
                        lastDraftPath = if (state.status == RecordingStatus.COMPLETED) state.outputFile?.absolutePath else current.lastDraftPath,
                        isStorageLow = state.isStorageLow,
                        episodeNumber = state.episodeNumber
                    )
                }

                if (state.status == RecordingStatus.COMPLETED && state.draftId.isNotEmpty()) {
                    diagnosticLogger.info(DiagnosticCategory.RECORDING, "RECORDING_FINALIZED", mapOf(LogKeys.DRAFT_ID to state.draftId))
                    
                    viewModelScope.launch {
                        _navigationEvents.send(state.draftId)
                    }
                }
            }
            .launchIn(viewModelScope)

        recordingSessionManager.amplitude
            .onEach { rawAmplitude ->
                val normalized = (rawAmplitude.toFloat() / 32767f).coerceIn(0f, 1f)
                _uiState.update { current ->
                    val isStoppedOrFailed = current.status == RecordingStatus.FAILED || current.status == RecordingStatus.IDLE
                    current.copy(currentAmplitude = if (isStoppedOrFailed) 0f else normalized)
                }
            }
            .launchIn(viewModelScope)
    }

    fun startRecording() {
        viewModelScope.launch {
            diagnosticLogger.debug(DiagnosticCategory.RECORDING, "RECORDING_AUTO_START")
            _uiState.update { it.copy(error = null, status = RecordingStatus.IDLE) }
            val promptId = uiState.value.currentPrompt?.id
            recordingSessionManager.startNewSession(promptId = promptId)
        }
    }

    fun stopRecording() {
        recordingSessionManager.stopSession()
        viewModelScope.launch {
            userSessionManager.setActivePromptId(null)
        }
    }

    fun pauseRecording() {
        recordingSessionManager.pauseSession()
    }

    fun resumeRecording() {
        recordingSessionManager.resumeSession()
    }

    fun cancelRecording() {
        recordingSessionManager.cancelSession()
        viewModelScope.launch {
            userSessionManager.setActivePromptId(null)
            _uiState.update { it.copy(error = null, status = RecordingStatus.IDLE) }
        }
    }

    fun nextPrompt() {
        if (uiState.value.isPromptLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPromptLoading = true) }
            try {
                val currentUser = authRepository.currentUser.value
                val currentUid = currentUser?.uid
                if (currentUid.isNullOrBlank() || (sessionUserId != null && currentUid != sessionUserId)) {
                    diagnosticLogger.warn(
                        DiagnosticCategory.RECORDING,
                        "NEXT_PROMPT_BLOCKED_ACCOUNT_MISMATCH",
                        mapOf("activeUser" to (currentUid ?: "null"), "sessionUser" to (sessionUserId ?: "null"))
                    )
                    return@launch
                }

                val currentPrompt = uiState.value.currentPrompt
                if (currentPrompt != null) {
                    val consumeResult = promptManager.markPromptConsumed(userId = currentUid, promptId = currentPrompt.id)
                    if (consumeResult.isFailure) {
                        diagnosticLogger.error(
                            DiagnosticCategory.RECORDING,
                            "PROMPT_CONSUMPTION_FAILED_ON_SKIP",
                            mapOf("userId" to currentUid, "promptId" to currentPrompt.id),
                            consumeResult.exceptionOrNull()
                        )
                        return@launch
                    }
                }

                val nextBatch = if (currentSessionBatch < 8) currentSessionBatch + 1 else 1
                when (val result = promptManager.getNextPromptForUser(userId = currentUid, startBatchNumber = nextBatch)) {
                    is PromptSelectionResult.Success -> {
                        _uiState.update { it.copy(currentPrompt = result.prompt, isPromptVisible = true) }
                        currentSessionBatch = result.batchNumber
                        userSessionManager.setActivePromptId(result.prompt.id)
                    }
                    is PromptSelectionResult.CatalogExhausted -> {
                        diagnosticLogger.info(DiagnosticCategory.RECORDING, "PROMPT_CATALOG_EXHAUSTED_ON_NEXT", mapOf("userId" to currentUid))
                        _uiState.update { it.copy(currentPrompt = null, isPromptVisible = false) }
                        userSessionManager.setActivePromptId(null)
                    }
                    is PromptSelectionResult.BatchExhausted -> {
                        _uiState.update { it.copy(currentPrompt = null, isPromptVisible = false) }
                        userSessionManager.setActivePromptId(null)
                    }
                    is PromptSelectionResult.Error -> {
                        diagnosticLogger.error(
                            DiagnosticCategory.RECORDING,
                            "NEXT_PROMPT_LOADING_FAILED",
                            mapOf("userId" to currentUid, "currentPromptId" to (currentPrompt?.id ?: "none")),
                            result.cause
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnosticLogger.error(
                    DiagnosticCategory.RECORDING,
                    "NEXT_PROMPT_UNEXPECTED_ERROR",
                    mapOf("currentPromptId" to (uiState.value.currentPrompt?.id ?: "none")),
                    e
                )
            } finally {
                _uiState.update { it.copy(isPromptLoading = false) }
            }
        }
    }
}

data class RecordingUiState(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val flowState: RecordingFlowState = RecordingFlowState.IDLE,
    val error: RecordingError? = null,
    val countdownSeconds: Int = 0,
    val durationSeconds: Long = 0,
    val currentOutputFile: String? = null,
    val lastDraftPath: String? = null,
    val isPromptVisible: Boolean = true,
    val currentPrompt: ReflectionPrompt? = null,
    val isPromptLoading: Boolean = false,
    val amplitudes: List<Float> = emptyList(),
    val currentAmplitude: Float = 0f,
    val isStorageLow: Boolean = false,
    val episodeNumber: Long? = null,
    val nextEpisodeNumberPreview: Long? = null
)

enum class RecordingFlowState {
    IDLE, RECORDING
}

sealed class RecordingEvent {
    object RequestStart : RecordingEvent()
}

sealed class RecordingError {
    object PermissionDenied : RecordingError()
    object HardwareInUse : RecordingError()
    object StorageFull : RecordingError()
    object ReservationFailed : RecordingError()
    object Unknown : RecordingError()
}
