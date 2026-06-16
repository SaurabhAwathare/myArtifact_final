package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.RecordingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finalizes the local processing chain and transitions the draft to REVIEW_REQUIRED.
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
            // Targeted finalization update
            recordingRepository.finalizeProcessing(draftId)
            
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
