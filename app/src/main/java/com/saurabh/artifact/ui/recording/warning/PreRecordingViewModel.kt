package com.saurabh.artifact.ui.recording.warning

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.data.local.RecordingStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PreRecordingViewModel @Inject constructor(
    private val recordingSessionManager: RecordingSessionManager,
    private val storageManager: com.saurabh.artifact.util.StorageManager,
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

    val uiState: StateFlow<PreRecordingWarningUiState> = combine(
        recordingSessionManager.sessionState,
        storageFlow()
    ) { state, isStorageLow ->
        PreRecordingWarningUiState(
            remainingSeconds = state.ritualRemainingSeconds,
            // Include PREPARING to avoid dead-lock UI if user re-enters during 1.5s pacing
            isRecordingActive = state.status == RecordingStatus.RECORDING || 
                               state.status == RecordingStatus.PAUSED ||
                               state.status == RecordingStatus.PREPARING,
            isStorageLow = isStorageLow || state.isStorageLow
        )
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PreRecordingWarningUiState(remainingSeconds = getInitialRemainingSeconds())
    )

    private fun storageFlow(): Flow<Boolean> = flow {
        while (true) {
            val low = storageManager.availableStorageMb < com.saurabh.artifact.util.StorageManager.LOW_STORAGE_THRESHOLD_MB
            emit(low)
            delay(5000) // Periodic check
        }
    }

    private val _eventFlow = MutableSharedFlow<PreRecordingWarningEvent>()
    val eventFlow = _eventFlow.asSharedFlow()

    init {
        restoreOrStartRitual()
        observeRecordingState()
        checkInitialStorage()
    }

    private fun checkInitialStorage() {
        // Force a storage check update in the service if possible
        // Actually, we can just check it here and update a local flag if needed, 
        // but sessionState is the source of truth. 
        // The service usually checks storage once started.
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

    fun cancel() {
        savedStateHandle.remove<Long>(KEY_RITUAL_END_TIME)
        recordingSessionManager.cancelRitual()
    }

    fun openStorageSettings(context: android.content.Context) {
        val intent = android.content.Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to general settings if specific one is not available
            val fallbackIntent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
            fallbackIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallbackIntent)
        }
    }
}

data class PreRecordingWarningUiState(
    val remainingSeconds: Int = 10,
    val isRecordingActive: Boolean = false,
    val isStorageLow: Boolean = false
)

sealed class PreRecordingWarningEvent {
    object NavigateToRecording : PreRecordingWarningEvent()
}
