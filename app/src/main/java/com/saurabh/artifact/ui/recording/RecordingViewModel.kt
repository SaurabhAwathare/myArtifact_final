package com.saurabh.artifact.ui.recording

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val promptRepository: PromptRepository,
    private val userSessionManager: UserSessionManager,
    private val recordingSessionManager: RecordingSessionManager,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private val _events = Channel<RecordingEvent>()
    val events = _events.receiveAsFlow()

    private var promptList: List<ReflectionPrompt> = emptyList()
    private var currentPromptIndex = 0

    init {
        loadPrompts()
        observeRecordingSession()
        
        // Immediate start for InstantRecord flow (Warning ritual handled in PreRecordingWarningScreen)
        _uiState.update { it.copy(flowState = RecordingFlowState.RECORDING) }

        viewModelScope.launch {
            // Signal UI to request permission and start if idle
            _events.send(RecordingEvent.RequestStart)
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
                    lastDraftId = if (state.status == RecordingStatus.COMPLETED) state.draftId else it.lastDraftId,
                    lastDraftPath = if (state.status == RecordingStatus.COMPLETED) state.outputFile?.absolutePath else it.lastDraftPath,
                    isStorageLow = state.isStorageLow
                ) }

                if (state.status == RecordingStatus.COMPLETED && state.draftId.isNotEmpty()) {
                    Log.d("RecordingViewModel", "Recording finalized via Manager. Draft ID: ${state.draftId}")
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
            Log.d("RecordingViewModel", "Auto-starting recording session via RecordingSessionManager")
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
}

data class RecordingUiState(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val flowState: RecordingFlowState = RecordingFlowState.IDLE,
    val error: RecordingError? = null,
    val countdownSeconds: Int = 0,
    val durationSeconds: Long = 0,
    val currentOutputFile: String? = null,
    val lastDraftId: String? = null,
    val lastDraftPath: String? = null,
    val isPromptVisible: Boolean = true,
    val currentPrompt: ReflectionPrompt? = null,
    val promptList: List<ReflectionPrompt> = emptyList(),
    val currentPromptIndex: Int = 0,
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
