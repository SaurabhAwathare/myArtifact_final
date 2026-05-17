package com.saurabh.artifact.audio

import com.saurabh.artifact.data.local.RecordingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingSessionManager @Inject constructor() {

    val recordingState = RecordingService.recordingState

    val isSessionActive: Flow<Boolean> = recordingState.map { 
        it.status == RecordingStatus.RECORDING || it.status == RecordingStatus.PAUSED 
    }

    fun isRecordingActive(): Boolean {
        val status = recordingState.value.status
        return status == RecordingStatus.RECORDING || status == RecordingStatus.PAUSED
    }

    fun shouldShowRitual(): Boolean {
        // If we are already recording or paused, we should not show the ritual (privacy warning + countdown)
        return !isRecordingActive()
    }
}
