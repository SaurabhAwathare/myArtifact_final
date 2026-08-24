package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.*
import com.saurabh.artifact.model.ProcessingStage
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.saurabh.artifact.util.WaveformProcessor
import java.io.File

@HiltWorker
class WaveformWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val draftDao: Lazy<DraftDao>,
    private val authRepository: com.saurabh.artifact.repository.AuthRepository,
    private val startupCoordinator: com.saurabh.artifact.startup.StartupCoordinator
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // WORKER LOCK: Ensure database encryption is ready before proceeding
        startupCoordinator.awaitComponent(com.saurabh.artifact.startup.StartupComponent.DATABASE)

        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        val userId = authRepository.currentUserId
        
        if (userId.isEmpty()) return@withContext Result.failure()

        try {
            val draft = draftDao.get().getDraftById(draftId, userId) ?: return@withContext Result.failure()
            val rawFile = draft.rawPcmPath?.let { File(it) } ?: File(draft.localAudioPath)
            
            if (!rawFile.exists()) {
                val pathStr = draft.rawPcmPath ?: draft.localAudioPath
                android.util.Log.e("WaveformWorker", "Waveform generation failed: Audio source missing at $pathStr")
                updateSubState(draftId, userId, null, "Raw audio file missing")
                return@withContext Result.failure()
            }

            updateSubState(draftId, userId, ProcessingStage.WAVEFORM_GENERATION)
            
            // High-fidelity extraction from PCM
            val waveformData = WaveformProcessor.extractFromPcm(rawFile, targetSize = 100)
            
            if (waveformData.isNotEmpty()) {
                // Targeted update: Save waveform and clear processing state
                draftDao.get().updateWaveformResult(draftId, userId, waveformData)
            }
            
            Result.success()
        } catch (e: Exception) {
            updateSubState(draftId, userId, null, "Waveform generation failed: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun updateSubState(id: String, userId: String, stage: ProcessingStage?, error: String? = null) {
        val newProcessing = when {
            error != null -> ProcessingStatus.Failed
            stage != null -> ProcessingStatus.Active(stage)
            else -> ProcessingStatus.Idle
        }
        draftDao.get().updateProcessingStatus(id, userId, newProcessing)
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
