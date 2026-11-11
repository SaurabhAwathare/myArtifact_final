package com.saurabh.artifact.ui.recording.warning

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.data.local.RecordingStatus
import com.saurabh.artifact.util.StorageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PreRecordingViewModel @Inject constructor(
    private val recordingSessionManager: RecordingSessionManager,
    private val storageManager: StorageManager,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        const val KEY_RITUAL_END_TIME = "ritual_end_time"
    }

    private data class StorageCheck(val isLow: Boolean, val isCritical: Boolean)

    private fun storageFlow(): Flow<StorageCheck> = flow {
        while (true) {
            val available = storageManager.availableStorageMb
            val low = available < StorageManager.LOW_STORAGE_THRESHOLD_MB
            val critical = !storageManager.isStorageAvailable()
            emit(StorageCheck(isLow = low, isCritical = critical))
            delay(5000)
        }
    }

    val uiState: StateFlow<PreRecordingWarningUiState> = combine(
        recordingSessionManager.sessionState,
        storageFlow()
    ) { state, storageCheck ->
        PreRecordingWarningUiState(
            remainingSeconds = state.ritualRemainingSeconds,
            // Include PREPARING to avoid dead-lock UI if user re-enters during pacing
            isRecordingActive = state.status == RecordingStatus.RECORDING || 
                               state.status == RecordingStatus.PAUSED ||
                               state.status == RecordingStatus.PREPARING,
            isStorageLow = storageCheck.isLow || state.isStorageLow,
            isStorageCritical = storageCheck.isCritical
        )
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PreRecordingWarningUiState(
            remainingSeconds = 0,
            isStorageLow = storageManager.availableStorageMb < StorageManager.LOW_STORAGE_THRESHOLD_MB,
            isStorageCritical = !storageManager.isStorageAvailable()
        )
    )

    private val _eventFlow = MutableSharedFlow<PreRecordingWarningEvent>()
    val eventFlow = _eventFlow.asSharedFlow()

    private var isNavigatingToRecord = false

    init {
        // Stage 3.6.3a: The mandatory 10-second countdown is removed.
        // Ensure any residual ritual state or saved countdown is cleared immediately.
        savedStateHandle.remove<Long>(KEY_RITUAL_END_TIME)
        recordingSessionManager.cancelRitual()
        observeRecordingState()
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

    fun canBeginRecording(): Boolean {
        val state = uiState.value
        return !isNavigatingToRecord && !state.isStorageCritical && !state.isRecordingActive
    }

    fun onBeginRecording(onProceed: () -> Unit) {
        if (!canBeginRecording()) return
        isNavigatingToRecord = true
        savedStateHandle.remove<Long>(KEY_RITUAL_END_TIME)
        recordingSessionManager.skipRitual()
        onProceed()
    }

    fun cancel() {
        savedStateHandle.remove<Long>(KEY_RITUAL_END_TIME)
        recordingSessionManager.cancelRitual()
        isNavigatingToRecord = false
    }

    fun openStorageSettings(context: Context) {
        val intent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        }
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        }
    }
}

data class PreRecordingWarningUiState(
    val remainingSeconds: Int = 0,
    val isRecordingActive: Boolean = false,
    val isStorageLow: Boolean = false,
    val isStorageCritical: Boolean = false
)

sealed class PreRecordingWarningEvent {
    object NavigateToRecording : PreRecordingWarningEvent()
}
