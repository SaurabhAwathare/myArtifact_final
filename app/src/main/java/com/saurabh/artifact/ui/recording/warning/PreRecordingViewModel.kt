package com.saurabh.artifact.ui.recording.warning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PreRecordingViewModel @Inject constructor(
    private val recordingSessionManager: com.saurabh.artifact.audio.RecordingSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(PreRecordingWarningUiState())
    val uiState: StateFlow<PreRecordingWarningUiState> = _uiState.asStateFlow()

    private val _eventFlow = MutableSharedFlow<PreRecordingWarningEvent>()
    val eventFlow = _eventFlow.asSharedFlow()

    private var countdownJob: Job? = null

    init {
        observeRecordingState()
        startCountdown()
    }

    private fun observeRecordingState() {
        viewModelScope.launch {
            recordingSessionManager.recordingState.collect { state ->
                if (state.status == com.saurabh.artifact.data.local.RecordingStatus.RECORDING ||
                    state.status == com.saurabh.artifact.data.local.RecordingStatus.PAUSED) {
                    _eventFlow.emit(PreRecordingWarningEvent.NavigateToRecording)
                }
            }
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (_uiState.value.remainingSeconds > 0) {
                delay(1000)
                _uiState.update { it.copy(remainingSeconds = it.remainingSeconds - 1) }
            }
        }
    }

    fun skipCountdown() {
        viewModelScope.launch {
            _eventFlow.emit(PreRecordingWarningEvent.NavigateToRecording)
        }
    }

    fun cancel() {
        countdownJob?.cancel()
    }
}

data class PreRecordingWarningUiState(
    val remainingSeconds: Int = 10
)

sealed class PreRecordingWarningEvent {
    object NavigateToRecording : PreRecordingWarningEvent()
}
