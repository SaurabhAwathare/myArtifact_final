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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@HiltWorker
class AudioNormalizationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val draftDao: DraftDao
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        
        try {
            updateSubState(draftId, ProcessingStage.NORMALIZING)
            
            // Simulation of audio normalization
            delay(2.seconds)
            
            Result.success()
        } catch (e: Exception) {
            updateSubState(draftId, null, "Normalization failed: ${e.message}")
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
                status = draft.status.copy(processing = newProcessing)
            ))
        }
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
