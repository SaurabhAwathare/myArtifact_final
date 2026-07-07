package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.audio.DraftDeletionManager
import com.saurabh.artifact.audio.RetentionPolicy
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
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
    private val storageManager: StorageManager,
    private val diagnosticLogger: DiagnosticLogger
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val isEmergency = inputData.getBoolean(KEY_EMERGENCY_MODE, false)
        
        if (isEmergency) {
            return performEmergencyCleanup()
        }

        val artifactId = inputData.getString(KEY_ARTIFACT_ID) ?: return Result.failure()
        
        diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "CLEANUP_STARTED", mapOf(LogKeys.ARTIFACT_ID to artifactId))
        
        return try {
            // 1. Find the draft in local database
            val draft = draftDao.getDraftByArtifactId(artifactId)
            
            if (draft != null) {
                // 2. Authoritative delete
                deletionManager.deleteDraft(draft.id)
                diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "CLEANUP_SUCCESS", mapOf(LogKeys.ARTIFACT_ID to artifactId))
            } else {
                diagnosticLogger.warn(DiagnosticCategory.WORKMANAGER, "CLEANUP_DRAFT_NOT_FOUND", mapOf(LogKeys.ARTIFACT_ID to artifactId))
            }
            
            Result.success()
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.WORKMANAGER, "CLEANUP_FAILED", mapOf(LogKeys.ARTIFACT_ID to artifactId), e)
            Result.retry()
        }
    }

    private suspend fun performEmergencyCleanup(): Result {
        diagnosticLogger.info(DiagnosticCategory.STORAGE, "EMERGENCY_CLEANUP_STARTED")
        
        val availableMb = storageManager.availableStorageMb
        if (availableMb > RetentionPolicy.EMERGENCY_STORAGE_THRESHOLD_MB) {
            diagnosticLogger.debug(DiagnosticCategory.STORAGE, "EMERGENCY_CLEANUP_SKIPPED", mapOf("availableMb" to availableMb))
            return Result.success()
        }

        return try {
            // Find all published artifacts that still have local files
            val publishedDrafts = draftDao.getDraftsByLifecycle(ArtifactLifecycle.PUBLISHED)
            diagnosticLogger.info(DiagnosticCategory.STORAGE, "EMERGENCY_CLEANUP_PURGING", mapOf("count" to publishedDrafts.size))
            
            publishedDrafts.forEach { draft ->
                deletionManager.deleteDraft(draft.id)
            }
            
            diagnosticLogger.info(DiagnosticCategory.STORAGE, "EMERGENCY_CLEANUP_SUCCESS")
            Result.success()
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.STORAGE, "EMERGENCY_CLEANUP_FAILED", throwable = e)
            Result.retry()
        }
    }

    companion object {
        const val KEY_ARTIFACT_ID = "artifact_id"
        const val KEY_EMERGENCY_MODE = "emergency_mode"
    }
}
