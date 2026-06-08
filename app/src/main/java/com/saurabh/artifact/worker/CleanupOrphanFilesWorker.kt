package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.data.local.DraftDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Reconciles the filesystem with the Room database to remove orphaned media files.
 * Runs on app startup and periodically to ensure storage hygiene.
 */
@HiltWorker
class CleanupOrphanFilesWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val draftDao: DraftDao,
    private val localDraftManager: LocalDraftManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("CleanupOrphanFilesWorker", "Starting orphan cleanup...")
        
        return try {
            val allDrafts = draftDao.getAllDrafts()
            localDraftManager.reconcileStorage(allDrafts)
            
            Log.d("CleanupOrphanFilesWorker", "Orphan cleanup complete.")
            Result.success()
        } catch (e: Exception) {
            Log.e("CleanupOrphanFilesWorker", "Error during orphan cleanup", e)
            Result.failure()
        }
    }
}
