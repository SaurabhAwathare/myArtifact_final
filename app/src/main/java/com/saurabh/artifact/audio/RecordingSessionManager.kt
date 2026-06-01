package com.saurabh.artifact.audio

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.RecordingStatus
import com.saurabh.artifact.repository.RecordingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackCoordinator: PlaybackCoordinator,
    private val recordingRepository: RecordingRepository,
    private val localDraftManager: LocalDraftManager,
    private val draftDao: DraftDao,
    private val deletionManager: DraftDeletionManager
) {

    val recordingState = RecordingService.recordingState

    private val _activeDraft = MutableStateFlow<ArtifactDraftEntity?>(null)
    val activeDraft: StateFlow<ArtifactDraftEntity?> = _activeDraft.asStateFlow()

    val isSessionActive: Flow<Boolean> = recordingState.map { 
        it.status == RecordingStatus.RECORDING || 
        it.status == RecordingStatus.PAUSED ||
        it.status == RecordingStatus.PREPARING
    }

    /**
     * Prepares for a new recording session by stopping any active playback.
     */
    fun prepareForRecording() {
        if (playbackCoordinator.isPlaying.value) {
            playbackCoordinator.stop()
        }
    }

    suspend fun startNewSession() {
        val currentStatus = recordingState.value.status
        if (currentStatus != RecordingStatus.IDLE && currentStatus != RecordingStatus.FAILED && currentStatus != RecordingStatus.COMPLETED) {
            Log.w("RecordingSessionManager", "startNewSession ignored: Already in state $currentStatus")
            return
        }

        prepareForRecording()

        val draftId = UUID.randomUUID().toString()
        val file = localDraftManager.createDraftFile(draftId, "wav")
        recordingRepository.createDraft(draftId, file.absolutePath, 0)
        val draft = draftDao.getDraftById(draftId)
        
        _activeDraft.value = draft
        
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra("draft_id", draftId)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopSession() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun pauseSession() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_PAUSE
        }
        context.startService(intent)
    }

    fun resumeSession() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_RESUME
        }
        context.startService(intent)
    }

    fun cancelSession() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_CANCEL
        }
        context.startService(intent)
        
        val draftId = _activeDraft.value?.id
        if (draftId != null) {
            // Use background scope to avoid blocking UI during cancellation purge
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                deletionManager.deleteDraft(draftId)
            }
        }
        _activeDraft.value = null
    }

    fun isRecordingActive(): Boolean {
        val status = recordingState.value.status
        return status == RecordingStatus.RECORDING || 
               status == RecordingStatus.PAUSED ||
               status == RecordingStatus.PREPARING
    }

    fun shouldShowRitual(): Boolean {
        // If we are already recording or paused, we should not show the ritual (privacy warning + countdown)
        return !isRecordingActive()
    }
}
