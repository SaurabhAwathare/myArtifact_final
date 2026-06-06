package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.audio.DraftDeletionManager
import com.saurabh.artifact.data.local.DraftDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background worker for reliable local file cleanup after an artifact is deleted.
 * Ensures that even if the process is killed, the recording file is eventually removed.
 */
@HiltWorker
class CleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val draftDao: DraftDao,
    private val deletionManager: DraftDeletionManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val artifactId = inputData.getString(KEY_ARTIFACT_ID) ?: return Result.failure()
        
        Log.d("CleanupWorker", "Starting local cleanup for artifact: $artifactId")
        
        return try {
            // 1. Find the draft in local database
            val draft = draftDao.getDraftByArtifactId(artifactId)
            
            if (draft != null) {
                // 2. Authoritative delete
                deletionManager.deleteDraft(draft.id)
                Log.d("CleanupWorker", "Successfully cleaned up local data for $artifactId")
            } else {
                Log.w("CleanupWorker", "No local draft found for artifact $artifactId")
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e("CleanupWorker", "Failed to cleanup local data for $artifactId", e)
            Result.retry()
        }
    }

    companion object {
        const val KEY_ARTIFACT_ID = "artifact_id"
    }
}
