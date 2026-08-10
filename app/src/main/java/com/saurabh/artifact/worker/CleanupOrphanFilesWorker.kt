package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.data.local.DatabaseMaintenanceManager
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
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
    private val draftDao: dagger.Lazy<DraftDao>,
    private val localDraftManager: LocalDraftManager,
    private val maintenanceManager: DatabaseMaintenanceManager,
    private val diagnosticLogger: DiagnosticLogger
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "ORPHAN_CLEANUP_STARTED")
        
        return try {
            // 1. Reconcile filesystem
            val allDrafts = draftDao.get().getAllDrafts()
            localDraftManager.reconcileStorage(allDrafts)
            
            // 2. Perform database maintenance (Pruning & VACUUM)
            maintenanceManager.runMaintenance()
            
            diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "ORPHAN_CLEANUP_SUCCESS")
            Result.success()
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.WORKMANAGER, "ORPHAN_CLEANUP_FAILED", throwable = e)
            Result.failure()
        }
    }
}
