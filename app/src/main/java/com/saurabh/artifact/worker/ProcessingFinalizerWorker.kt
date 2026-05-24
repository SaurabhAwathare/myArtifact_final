package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.repository.RecordingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finalizes the local processing chain and transitions the draft to READY_TO_REVIEW.
 */
@HiltWorker
class ProcessingFinalizerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val recordingRepository: RecordingRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        
        try {
            val draft = recordingRepository.getDraft(draftId) ?: return@withContext Result.failure()
            
            // Transition to READY_TO_REVIEW
            recordingRepository.updateDraft(draft.copy(
                draftState = ArtifactDraftState.READY_TO_REVIEW,
                updatedAt = System.currentTimeMillis()
            ))
            
            Log.d("ProcessingFinalizer", "Local processing completed for $draftId. Ready for review.")
            Result.success()
        } catch (e: Exception) {
            Log.e("ProcessingFinalizer", "Failed to finalize processing", e)
            Result.retry()
        }
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
