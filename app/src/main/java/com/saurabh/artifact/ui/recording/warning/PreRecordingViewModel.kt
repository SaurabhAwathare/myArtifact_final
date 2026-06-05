package com.saurabh.artifact.ui.recording.warning

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.data.local.RecordingStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PreRecordingViewModel @Inject constructor(
    private val recordingSessionManager: RecordingSessionManager,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val KEY_RITUAL_END_TIME = "ritual_end_time"
        private const val RITUAL_DURATION_SECONDS = 10
    }

    // Flicker Prevention: Calculate initial remaining time from SavedStateHandle before UI binds
    private fun getInitialRemainingSeconds(): Int {
        val savedEndTime = savedStateHandle.get<Long>(KEY_RITUAL_END_TIME)
        return if (savedEndTime != null) {
            val remaining = ((savedEndTime - System.currentTimeMillis()) / 1000).toInt()
            remaining.coerceAtLeast(0)
        } else {
            RITUAL_DURATION_SECONDS
        }
    }

    val uiState: StateFlow<PreRecordingWarningUiState> = recordingSessionManager.sessionState
        .map { state ->
            PreRecordingWarningUiState(
                remainingSeconds = state.ritualRemainingSeconds,
                // Include PREPARING to avoid dead-lock UI if user re-enters during 1.5s pacing
                isRecordingActive = state.status == RecordingStatus.RECORDING || 
                                   state.status == RecordingStatus.PAUSED ||
                                   state.status == RecordingStatus.PREPARING
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreRecordingWarningUiState(remainingSeconds = getInitialRemainingSeconds())
        )

    private val _eventFlow = MutableSharedFlow<PreRecordingWarningEvent>()
    val eventFlow = _eventFlow.asSharedFlow()

    init {
        restoreOrStartRitual()
        observeRecordingState()
    }

    private fun restoreOrStartRitual() {
        val savedEndTime = savedStateHandle.get<Long>(KEY_RITUAL_END_TIME)
        val currentTime = System.currentTimeMillis()

        if (savedEndTime != null) {
            val remaining = ((savedEndTime - currentTime) / 1000).toInt()
            if (remaining > 0) {
                recordingSessionManager.startRitual(remaining)
            } else {
                recordingSessionManager.skipRitual()
            }
        } else {
            val endTime = currentTime + (RITUAL_DURATION_SECONDS * 1000)
            savedStateHandle[KEY_RITUAL_END_TIME] = endTime
            recordingSessionManager.startRitual(RITUAL_DURATION_SECONDS)
        }
    }

    private fun observeRecordingState() {
        viewModelScope.launch {
            recordingSessionManager.sessionState.collect { state ->
                // Auto-navigate if recording is already happening in background
                if (state.status == RecordingStatus.RECORDING ||
                    state.status == RecordingStatus.PAUSED ||
                    state.status == RecordingStatus.PREPARING) {
                    _eventFlow.emit(PreRecordingWarningEvent.NavigateToRecording)
                }
            }
        }
    }

    fun skipCountdown() {
        savedStateHandle[KEY_RITUAL_END_TIME] = System.currentTimeMillis()
        recordingSessionManager.skipRitual()
    }

    fun cancel() {
        savedStateHandle.remove<Long>(KEY_RITUAL_END_TIME)
        recordingSessionManager.cancelRitual()
    }
}

data class PreRecordingWarningUiState(
    val remainingSeconds: Int = 10,
    val isRecordingActive: Boolean = false
)

sealed class PreRecordingWarningEvent {
    object NavigateToRecording : PreRecordingWarningEvent()
}
