package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.audio.DraftDeletionManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider

/**
 * Retries failed draft deletions with exponential backoff.
 * Delegated to DraftDeletionManager to ensure authoritative logic.
 */
@HiltWorker
class DeletionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val deletionManagerProvider: Provider<DraftDeletionManager>
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return Result.failure()
        
        Log.d("DeletionWorker", "Retrying deletion for draft: $draftId")
        
        return try {
            deletionManagerProvider.get().deleteDraft(draftId)
            Result.success()
        } catch (e: Exception) {
            Log.e("DeletionWorker", "Retry failed for $draftId", e)
            Result.retry()
        }
    }

    companion object {
        const val KEY_DRAFT_ID = "draft_id"
    }
}
