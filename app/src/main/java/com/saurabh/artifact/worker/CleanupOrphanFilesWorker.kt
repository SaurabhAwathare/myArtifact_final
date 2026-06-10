package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.data.local.DatabaseMaintenanceManager
import com.saurabh.artifact.data.local.DraftDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Reconciles the filesystem with the Room database to remove orphaned media files
 * and performs database maintenance (pruning/compaction).
 * Runs on app startup and periodically to ensure storage hygiene.
 */
@HiltWorker
class CleanupOrphanFilesWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val draftDao: DraftDao,
    private val localDraftManager: LocalDraftManager,
    private val maintenanceManager: DatabaseMaintenanceManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("CleanupOrphanFilesWorker", "Starting orphan cleanup and database maintenance...")
        
        return try {
            // 1. Reconcile filesystem
            val allDrafts = draftDao.getAllDrafts()
            localDraftManager.reconcileStorage(allDrafts)
            
            // 2. Perform database maintenance (Pruning & VACUUM)
            maintenanceManager.runMaintenance()
            
            Log.d("CleanupOrphanFilesWorker", "Orphan cleanup and database maintenance complete.")
            Result.success()
        } catch (e: Exception) {
            Log.e("CleanupOrphanFilesWorker", "Error during cleanup/maintenance", e)
            Result.failure()
        }
    }
}
