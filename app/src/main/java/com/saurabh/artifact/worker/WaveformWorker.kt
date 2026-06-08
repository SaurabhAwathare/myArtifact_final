package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.*
import com.saurabh.artifact.model.ProcessingStage
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
    private val draftDao: DraftDao
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        
        try {
            val draft = draftDao.getDraftById(draftId) ?: return@withContext Result.failure()
            val rawFile = draft.rawPcmPath?.let { File(it) } ?: File(draft.localAudioPath)
            
            if (!rawFile.exists()) {
                updateSubState(draftId, null, "Raw audio file missing")
                return@withContext Result.failure()
            }

            updateSubState(draftId, ProcessingStage.WAVEFORM_GENERATION)
            
            // High-fidelity extraction from PCM
            val waveformData = WaveformProcessor.extractFromPcm(rawFile, targetSize = 100)
            
            if (waveformData.isNotEmpty()) {
                draftDao.update(draft.copy(
                    amplitudeData = waveformData,
                    status = draft.status.copy(processing = ProcessingStatus.Idle), // Mark this stage idle
                    updatedAt = System.currentTimeMillis()
                ))
            }
            
            Result.success()
        } catch (e: Exception) {
            updateSubState(draftId, null, "Waveform generation failed: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun updateSubState(id: String, stage: ProcessingStage?, error: String? = null) {
        draftDao.getDraftById(id)?.let { draft ->
            val newProcessing = when {
                error != null -> ProcessingStatus.Failed
                stage != null -> ProcessingStatus.Active(stage)
                else -> ProcessingStatus.Idle
            }
            draftDao.update(draft.copy(
                status = draft.status.copy(processing = newProcessing),
                updatedAt = System.currentTimeMillis()
            ))
        }
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
