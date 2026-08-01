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
    private val authRepository: com.saurabh.artifact.repository.AuthRepository,
    private val promptRepository: PromptRepository,
    private val promptManager: com.saurabh.artifact.domain.prompt.ReflectionPromptManager,
    private val userSessionManager: UserSessionManager,
    private val recordingSessionManager: RecordingSessionManager,
    private val savedStateHandle: SavedStateHandle,
    private val diagnosticLogger: DiagnosticLogger
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private val _events = Channel<RecordingEvent>(capacity = Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _navigationEvents = Channel<String>(capacity = Channel.BUFFERED)
    val navigationEvents = _navigationEvents.receiveAsFlow()

    private var promptList: List<ReflectionPrompt> = emptyList()
    private var currentPromptIndex = 0

    init {
        if (authRepository.currentUser.value == null) {
            diagnosticLogger.warn(DiagnosticCategory.AUTH, "RECORDING_BLOCKED_AUTH", mapOf("reason" to "USER_NULL"))
        } else {
            loadPrompts()
            observeRecordingSession()
            
            // Immediate start for InstantRecord flow (Warning ritual handled in PreRecordingWarningScreen)
            _uiState.update { it.copy(flowState = RecordingFlowState.RECORDING) }
            
            viewModelScope.launch {
                _events.send(RecordingEvent.RequestStart)
            }
        }
    }

    private fun loadPrompts() {
        viewModelScope.launch {
            // Ensure DB is initialized
            promptRepository.initializeIfEmpty()

            // First load all prompts and wait for data
            promptRepository.getAllPrompts()
                .first { it.isNotEmpty() }
                .let { allPrompts ->
                    promptList = allPrompts.shuffled()
                    
                    // Priority 1: Navigation Argument
                    val navPromptEncoded = savedStateHandle.get<String>("prompt")
                    val navPrompt = navPromptEncoded?.let {
                        try {
                            URLDecoder.decode(it, StandardCharsets.UTF_8.toString())
                        } catch (_: Exception) {
                            it
                        }
                    }
                    
                    // Priority 2: Session-active prompt
                    val activePromptId = userSessionManager.activePromptId.first()
                    
                    var targetPrompt = if (navPrompt != null) {
                        promptList.find { it.question.equals(navPrompt.trim(), ignoreCase = true) }
                    } else null

                    if (targetPrompt == null && navPrompt != null) {
                        // Create a temporary prompt for the one passed via navigation if not in DB
                        targetPrompt = ReflectionPrompt(
                            id = "nav_${System.currentTimeMillis()}",
                            category = PromptCategory.AI_GUIDED,
                            question = navPrompt.trim()
                        )
                        promptList = listOf(targetPrompt) + promptList
                    }

                    if (targetPrompt == null && activePromptId != null) {
                        targetPrompt = promptList.find { it.id == activePromptId }
                    }
                    
                    if (targetPrompt != null) {
                        currentPromptIndex = promptList.indexOf(targetPrompt)
                        _uiState.update { it.copy(
                            currentPrompt = targetPrompt,
                            promptList = promptList,
                            currentPromptIndex = currentPromptIndex,
                            isPromptVisible = true
                        ) }
                    } else {
                        val firstPrompt = promptList[currentPromptIndex]
                        _uiState.update { it.copy(
                            currentPrompt = firstPrompt,
                            promptList = promptList,
                            currentPromptIndex = currentPromptIndex,
                            isPromptVisible = true
                        ) }
                        userSessionManager.setActivePromptId(firstPrompt.id)
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
        if (promptList.isNotEmpty()) {
            currentPromptIndex = (currentPromptIndex + 1) % promptList.size
            val nextPrompt = promptList[currentPromptIndex]
            _uiState.update { it.copy(
                currentPrompt = nextPrompt,
                currentPromptIndex = currentPromptIndex
            ) }
            viewModelScope.launch {
                userSessionManager.setActivePromptId(nextPrompt.id)
            }
        }
    }

    fun updatePromptIndex(index: Int) {
        if (promptList.isNotEmpty() && index in promptList.indices) {
            currentPromptIndex = index
            val nextPrompt = promptList[currentPromptIndex]
            _uiState.update { it.copy(
                currentPrompt = nextPrompt,
                currentPromptIndex = currentPromptIndex
            ) }
            viewModelScope.launch {
                userSessionManager.setActivePromptId(nextPrompt.id)
            }
        }
    }

    fun refreshAIPrompt(context: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPromptLoading = true) }
            try {
                // Determine time of day for context
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val timeOfDay = when (hour) {
                    in 5..11 -> "morning"
                    in 12..16 -> "afternoon"
                    in 17..21 -> "evening"
                    else -> "night"
                }

                val aiPrompt = promptManager.getSmartReflectionPrompt(
                    emotion = null, // Could be derived from transcription in the future
                    context = context,
                    timeOfDay = timeOfDay
                )

                // Add to list and select it
                promptList = listOf(aiPrompt) + promptList
                currentPromptIndex = 0
                _uiState.update { it.copy(
                    currentPrompt = aiPrompt,
                    promptList = promptList,
                    currentPromptIndex = 0,
                    isPromptLoading = false
                ) }
                userSessionManager.setActivePromptId(aiPrompt.id)
                
                diagnosticLogger.info(
                    DiagnosticCategory.STUDIO, 
                    "AI_PROMPT_REFRESHED", 
                    mapOf(LogKeys.PROMPT_ID to aiPrompt.id)
                )
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.STUDIO, "AI_PROMPT_REFRESH_FAILED", throwable = e)
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
    val promptList: List<ReflectionPrompt> = emptyList(),
    val currentPromptIndex: Int = 0,
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
