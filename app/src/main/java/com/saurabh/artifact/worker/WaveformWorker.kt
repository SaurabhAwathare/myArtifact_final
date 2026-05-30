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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@HiltWorker
class WaveformWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val draftDao: DraftDao
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        
        try {
            updateSubState(draftId, ArtifactDraftState.WAVEFORM_GENERATION)
            
            // Simulation of waveform extraction
            delay(1500)
            
            Result.success()
        } catch (e: Exception) {
            updateSubState(draftId, ArtifactDraftState.ERROR, "Waveform generation failed: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun updateSubState(id: String, state: ArtifactDraftState, reason: String? = null) {
        val draft = draftDao.getDraftById(id) ?: return
        draftDao.update(draft.copy(
            draftState = state,
            interruptionReason = reason,
            updatedAt = System.currentTimeMillis()
        ))
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
