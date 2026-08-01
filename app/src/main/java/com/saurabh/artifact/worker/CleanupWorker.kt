package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.audio.DraftDeletionManager
import com.saurabh.artifact.audio.MediaCache
import com.saurabh.artifact.audio.RetentionPolicy
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.ArtifactDao
import com.saurabh.artifact.data.local.UploadTaskDao
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.LocalCleanupStatus
import com.saurabh.artifact.util.StorageManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import androidx.room.withTransaction
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/**
 * Background worker for reliable local file cleanup after an artifact is deleted
 * or when retention period expires.
 * 
 * Authoritatively owns the localCleanupStatus state machine and physical asset removal.
 */
@OptIn(UnstableApi::class)
@HiltWorker
class CleanupWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val draftDao: DraftDao,
    private val artifactDao: ArtifactDao,
    private val uploadTaskDao: UploadTaskDao,
    private val database: AppDatabase,
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
            // 1. Find the draft in local database (Robust Lookup - System Agnostic for Maintenance)
            var draft = draftDao.internalGetDraftByIdAgnostic(artifactId)
            if (draft == null) {
                draft = draftDao.internalGetDraftByArtifactIdAgnostic(artifactId)
            }
            
            if (draft == null) {
                // This is an expected idempotent condition. The draft may have been removed already by:
                // 1. A manual deletion worker (cleanup_$artifactId)
                // 2. A previously successful retention worker
                // 3. Periodic DatabaseMaintenanceManager pruning
                // 4. Recovery at startup
                diagnosticLogger.info(
                    DiagnosticCategory.WORKMANAGER, 
                    "CLEANUP_DRAFT_NOT_FOUND", 
                    mapOf(LogKeys.ARTIFACT_ID to artifactId, "reason" to "idempotent_skip")
                )
                // Also ensure feed record is gone even if draft record is missing
                artifactDao.deleteById(artifactId)
                return Result.success()
            }

            // 2. Transition to CLEANING state
            draftDao.updateLocalCleanupStatus(draft.id, draft.userId, LocalCleanupStatus.CLEANING)

            // 3. Authoritative physical purge
            deletionManager.performPhysicalPurge(draft)

            // 4. Purge MediaCache if URL is present
            MediaCache.removeResource(draft.uploadedAudioUrl)

            // 5. Hard Delete: Finalize state and remove DB records
            database.withTransaction {
                draftDao.updateLocalCleanupStatus(draft.id, draft.userId, LocalCleanupStatus.COMPLETED)
                uploadTaskDao.deleteByDraftId(draft.id)
                artifactDao.deleteById(artifactId) // Clear feed record
                draftDao.internalDeleteByIdAgnostic(draft.id)
            }

            diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "CLEANUP_SUCCESS", mapOf(LogKeys.ARTIFACT_ID to artifactId))
            Result.success()
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.WORKMANAGER, "CLEANUP_FAILED", mapOf(LogKeys.ARTIFACT_ID to artifactId), e)
            
            // 6. Handle Retries
            val draft = draftDao.internalGetDraftByIdAgnostic(artifactId) 
                ?: draftDao.internalGetDraftByArtifactIdAgnostic(artifactId)
            
            if (draft != null) {
                val newRetryCount = draft.cleanupRetryCount + 1
                if (newRetryCount >= MAX_RETRIES) {
                    draftDao.updateLocalCleanupStatus(draft.id, draft.userId, LocalCleanupStatus.FAILED_TERMINAL)
                    return Result.failure()
                } else {
                    draftDao.updateCleanupRetryCount(draft.id, draft.userId, newRetryCount)
                    draftDao.updateLocalCleanupStatus(draft.id, draft.userId, LocalCleanupStatus.FAILED_RETRYABLE)
                    return Result.retry()
                }
            }
            Result.failure()
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
            val publishedDrafts = draftDao.getDraftsByLifecycleAgnostic(ArtifactLifecycle.PUBLISHED)
            diagnosticLogger.info(DiagnosticCategory.STORAGE, "EMERGENCY_CLEANUP_PURGING", mapOf("count" to publishedDrafts.size))
            
            publishedDrafts.forEach { draft ->
                deletionManager.performPhysicalPurge(draft)
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
        private const val MAX_RETRIES = 5
    }
}
