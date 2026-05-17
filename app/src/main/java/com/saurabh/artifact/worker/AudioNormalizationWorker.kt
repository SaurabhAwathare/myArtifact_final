package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.model.ProcessingStage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
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
            updateSubState(draftId, ArtifactDraftState.NORMALIZING)
            
            // Simulation of audio normalization
            delay(2000) 
            
            Result.success()
        } catch (e: Exception) {
            updateSubState(draftId, ArtifactDraftState.ERROR)
            Result.retry()
        }
    }

    private suspend fun updateSubState(id: String, state: ArtifactDraftState) {
        val draft = draftDao.getDraftById(id) ?: return
        draftDao.update(draft.copy(
            draftState = state,
            updatedAt = System.currentTimeMillis()
        ))
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
