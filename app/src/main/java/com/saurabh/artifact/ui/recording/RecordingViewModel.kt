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
import com.saurabh.artifact.model.PromptCategory
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.repository.PromptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject

@HiltViewModel
class RecordingViewModel @Inject constructor(
    authRepository: com.saurabh.artifact.repository.AuthRepository,
    private val promptRepository: PromptRepository,
    private val promptManager: com.saurabh.artifact.domain.prompt.ReflectionPromptManager,
    private val userSessionManager: UserSessionManager,
    private val recordingSessionManager: RecordingSessionManager,
    private val savedStateHandle: SavedStateHandle,
    private val diagnosticLogger: DiagnosticLogger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private val _events = Channel<RecordingEvent>(capacity = Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _navigationEvents = Channel<String>(capacity = Channel.BUFFERED)
    val navigationEvents = _navigationEvents.receiveAsFlow()

    init {
        if (authRepository.currentUser.value == null) {
            diagnosticLogger.warn(DiagnosticCategory.AUTH, "RECORDING_BLOCKED_AUTH", mapOf("reason" to "USER_NULL"))
        } else {
            loadInitialPrompt()
            observeRecordingSession()
            
            // Immediate start for InstantRecord flow
            _uiState.update { it.copy(flowState = RecordingFlowState.RECORDING) }
            
            viewModelScope.launch {
                _events.send(RecordingEvent.RequestStart)
            }
        }
    }

    private fun loadInitialPrompt() {
        viewModelScope.launch {
            promptRepository.initializeIfEmpty()

            // Priority 1: Navigation Argument
            val navPromptEncoded = savedStateHandle.get<String>("prompt")
            val navPrompt = navPromptEncoded?.let {
                try {
                    URLDecoder.decode(it, StandardCharsets.UTF_8.toString())
                } catch (_: Exception) {
                    it
                }
            }

            if (navPrompt != null) {
                val targetPrompt = ReflectionPrompt(
                    id = "nav_${System.currentTimeMillis()}",
                    category = PromptCategory.GENERAL,
                    question = navPrompt.trim()
                )
                _uiState.update { it.copy(currentPrompt = targetPrompt, isPromptVisible = true) }
                return@launch
            }

            // Priority 2: Session-active prompt (if not already consumed)
            val activePromptId = userSessionManager.activePromptId.first()
            if (activePromptId != null) {
                // We should ideally check if it's consumed, but for simplicity we fetch it if it exists
                // The repo will handle variety if we ask for a new one.
                // For now, let's just get a fresh one if we don't have one cached to ensure non-repetition
                // if the user restarts after seeing one but before "consuming" it.
                // BUT the requirement says "survive app restart". 
                // So we check if the active one is still valid.
                val allPrompts = promptRepository.getAllPrompts().first()
                val activePrompt = allPrompts.find { it.id == activePromptId }
                
                if (activePrompt != null && (!activePrompt.isConsumed)) { 
                    _uiState.update { it.copy(currentPrompt = activePrompt, isPromptVisible = true) }
                    return@launch
                }
            }

            // Priority 3: Fresh eligible prompt
            val freshPrompt = promptManager.getNextPrompt()
            _uiState.update { it.copy(currentPrompt = freshPrompt, isPromptVisible = true) }
            userSessionManager.setActivePromptId(freshPrompt.id)
        }
    }

    private fun observeRecordingSession() {
        recordingSessionManager.sessionState
            .onEach { state ->
                val error = when (state.errorCode) {
                    "PERMISSION_DENIED" -> RecordingError.PermissionDenied
                    "HARDWARE_IN_USE" -> RecordingError.HardwareInUse
                    "STORAGE_FULL" -> RecordingError.StorageFull
                    null -> if (state.status == RecordingStatus.FAILED) RecordingError.Unknown else null
                    else -> RecordingError.Unknown
                }

                _uiState.update { it.copy(
                    status = state.status,
                    error = error,
                    durationSeconds = state.durationSeconds,
                    currentOutputFile = state.outputFile?.absolutePath,
                    amplitudes = state.amplitudes,
                    lastDraftPath = if (state.status == RecordingStatus.COMPLETED) state.outputFile?.absolutePath else it.lastDraftPath,
                    isStorageLow = state.isStorageLow
                ) }

                if (state.status == RecordingStatus.COMPLETED && state.draftId.isNotEmpty()) {
                    diagnosticLogger.info(DiagnosticCategory.RECORDING, "RECORDING_FINALIZED", mapOf(LogKeys.DRAFT_ID to state.draftId))
                    
                    // Mark as consumed on successful recording
                    uiState.value.currentPrompt?.id?.let { id ->
                        promptRepository.markAsConsumed(id)
                    }
                    
                    viewModelScope.launch {
                        _navigationEvents.send(state.draftId)
                    }
                }
            }
            .launchIn(viewModelScope)

        recordingSessionManager.amplitude
            .onEach { rawAmplitude ->
                val normalized = (rawAmplitude.toFloat() / 32767f).coerceIn(0f, 1f)
                _uiState.update { it.copy(currentAmplitude = normalized) }
            }
            .launchIn(viewModelScope)
    }

    fun startRecording() {
        viewModelScope.launch {
            diagnosticLogger.debug(DiagnosticCategory.RECORDING, "RECORDING_AUTO_START")
            recordingSessionManager.startNewSession()
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
        }
    }

    fun nextPrompt() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPromptLoading = true) }
            
            // Mark current as consumed
            uiState.value.currentPrompt?.id?.let { id ->
                promptRepository.markAsConsumed(id)
            }
            
            // Fetch new one
            val nextPrompt = promptManager.getNextPrompt()
            _uiState.update { it.copy(
                currentPrompt = nextPrompt,
                isPromptLoading = false
            ) }
            userSessionManager.setActivePromptId(nextPrompt.id)
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
    val isStorageLow: Boolean = false
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
    object Unknown : RecordingError()
}
