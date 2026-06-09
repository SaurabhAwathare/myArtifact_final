package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.audio.DraftDeletionManager
import com.saurabh.artifact.audio.RetentionPolicy
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.util.StorageManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background worker for reliable local file cleanup after an artifact is deleted
 * or when retention period expires.
 * Also supports emergency cleanup if storage is low.
 */
@HiltWorker
class CleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val draftDao: DraftDao,
    private val deletionManager: DraftDeletionManager,
    private val storageManager: StorageManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val isEmergency = inputData.getBoolean(KEY_EMERGENCY_MODE, false)
        
        if (isEmergency) {
            return performEmergencyCleanup()
        }

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

    private suspend fun performEmergencyCleanup(): Result {
        Log.i("CleanupWorker", "Starting emergency storage cleanup...")
        
        val availableMb = storageManager.getAvailableStorageMb()
        if (availableMb > RetentionPolicy.EMERGENCY_STORAGE_THRESHOLD_MB) {
            Log.d("CleanupWorker", "Storage still above threshold ($availableMb MB). Skipping emergency cleanup.")
            return Result.success()
        }

        return try {
            // Find all published artifacts that still have local files
            val publishedDrafts = draftDao.getDraftsByLifecycle(ArtifactLifecycle.PUBLISHED)
            Log.i("CleanupWorker", "Found ${publishedDrafts.size} published drafts to purge for storage relief.")
            
            publishedDrafts.forEach { draft ->
                deletionManager.deleteDraft(draft.id)
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e("CleanupWorker", "Emergency cleanup failed", e)
            Result.retry()
        }
    }

    companion object {
        const val KEY_ARTIFACT_ID = "artifact_id"
        const val KEY_EMERGENCY_MODE = "emergency_mode"
    }
}
