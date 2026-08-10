package com.saurabh.artifact.audio

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.model.LocalCleanupStatus
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.worker.CleanupWorker
import dagger.Lazy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/**
 * Centralized manager for orchestrating the "emotional disappearance" of artifacts.
 * Handles optimistic UI state, remote deletion triggers, and local cleanup scheduling.
 * 
 * Phase 2: Now manages the localCleanupStatus state machine and startup recovery.
 */
@OptIn(UnstableApi::class)
@Singleton
class ArtifactCleanupManager @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val authRepository: com.saurabh.artifact.repository.AuthRepository,
    private val draftDao: Lazy<DraftDao>,
    private val workManager: WorkManager,
) {
    private val managerScope = CoroutineScope(Dispatchers.IO)
    private val _deletingArtifactIds = MutableStateFlow<Set<String>>(emptySet())
    val deletingArtifactIds = _deletingArtifactIds.asStateFlow()

    /**
     * Resumes any cleanups that were interrupted by process death or reboot.
     * Should be called on application startup.
     */
    fun resumeUnfinishedCleanups() {
        managerScope.launch {
            try {
                val unfinished = draftDao.get().getUnfinishedCleanups()
                unfinished.forEach { draft ->
                    ArtifactLogger.i(DiagnosticCategory.WORKMANAGER, "CLEANUP_RESUMING", mapOf("draftId" to draft.id))
                    scheduleLocalCleanup(draft.id)
                }
            } catch (e: Exception) {
                ArtifactLogger.e(DiagnosticCategory.WORKMANAGER, "CLEANUP_RECOVERY_FAILED", throwable = e)
            }
        }
    }

    /**
     * Cleans up any stale temporary decrypted files in the cache directory.
     */
    fun cleanStaleTempFiles(cacheDir: File) {
        managerScope.launch {
            try {
                cacheDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("decrypted_") && file.name.endsWith(".m4a")) {
                        val deleted = file.delete()
                        if (deleted) {
                            ArtifactLogger.d(DiagnosticCategory.STORAGE, "TEMP_FILE_CLEANUP_SUCCESS", mapOf("file_name" to file.name))
                        }
                    }
                }
            } catch (e: Exception) {
                ArtifactLogger.e(DiagnosticCategory.STORAGE, "TEMP_FILE_CLEANUP_FAILED", throwable = e)
            }
        }
    }

    /**
     * Initiates a resilient deletion flow for a published artifact.
     */
    suspend fun deleteArtifact(artifactId: String): Result<Unit> {
        val userId = authRepository.currentUserId
        if (userId.isEmpty()) return Result.failure(com.saurabh.artifact.model.AppError.Unauthenticated())
        
        _deletingArtifactIds.value += artifactId
        return try {
            // 1. Initialize local cleanup state
            draftDao.get().updateLocalCleanupStatusByArtifactId(artifactId, userId, LocalCleanupStatus.PENDING)
            
            // 2. Trigger remote deletion
            val result = artifactRepository.performRemoteDelete(artifactId)
            
            if (result.isSuccess) {
                ArtifactLogger.i(DiagnosticCategory.PUBLISH, "ARTIFACT_REMOTE_DELETION_SUCCESS", mapOf("artifactId" to artifactId))
                scheduleLocalCleanup(artifactId)
            } else {
                ArtifactLogger.e(DiagnosticCategory.PUBLISH, "ARTIFACT_REMOTE_DELETION_FAILED", mapOf("artifactId" to artifactId))
                // Do NOT schedule local cleanup if remote failed, to allow user retry
            }
            result
        } finally {
            _deletingArtifactIds.value -= artifactId
        }
    }

    /**
     * Deletes a local draft and its associated files.
     */
    suspend fun deleteDraft(draftId: String): Result<Unit> {
        val userId = authRepository.currentUserId
        if (userId.isEmpty()) return Result.failure(com.saurabh.artifact.model.AppError.Unauthenticated())

        _deletingArtifactIds.value += draftId
        return try {
            draftDao.get().updateLocalCleanupStatus(draftId, userId, LocalCleanupStatus.PENDING)
            scheduleLocalCleanup(draftId)
            ArtifactLogger.i(DiagnosticCategory.DRAFT, "DRAFT_LOCAL_DELETION_QUEUED", mapOf("draftId" to draftId))
            Result.success(Unit)
        } catch (e: Exception) {
            ArtifactLogger.e(DiagnosticCategory.DRAFT, "DRAFT_LOCAL_DELETION_FAILED", mapOf("draftId" to draftId), e)
            Result.failure(e)
        } finally {
            _deletingArtifactIds.value -= draftId
        }
    }

    /**
     * Schedules a delayed cleanup of local files for a published artifact.
     */
    fun scheduleRetentionCleanup(artifactId: String) {
        val inputData = Data.Builder()
            .putString(CleanupWorker.KEY_ARTIFACT_ID, artifactId)
            .build()

        val cleanupRequest = OneTimeWorkRequestBuilder<CleanupWorker>()
            .setInitialDelay(RetentionPolicy.DEFAULT_RETENTION_DAYS, RetentionPolicy.RETENTION_TIME_UNIT)
            .setInputData(inputData)
            .addTag("retention_cleanup_$artifactId")
            .build()

        workManager.enqueueUniqueWork(
            "retention_cleanup_$artifactId",
            ExistingWorkPolicy.KEEP,
            cleanupRequest
        )
    }

    /**
     * Triggers an immediate sweep for all published artifacts if storage is low.
     */
    fun triggerEmergencyCleanup() {
        val inputData = Data.Builder()
            .putBoolean(CleanupWorker.KEY_EMERGENCY_MODE, true)
            .build()

        val cleanupRequest = OneTimeWorkRequestBuilder<CleanupWorker>()
            .setInputData(inputData)
            .addTag("emergency_cleanup")
            .build()

        workManager.enqueueUniqueWork(
            "emergency_cleanup",
            ExistingWorkPolicy.REPLACE,
            cleanupRequest
        )
    }

    private fun scheduleLocalCleanup(artifactId: String) {
        val inputData = Data.Builder()
            .putString(CleanupWorker.KEY_ARTIFACT_ID, artifactId)
            .build()

        val cleanupRequest = OneTimeWorkRequestBuilder<CleanupWorker>()
            .setInputData(inputData)
            .addTag("cleanup_$artifactId")
            .build()

        workManager.enqueueUniqueWork(
            "cleanup_$artifactId",
            ExistingWorkPolicy.REPLACE,
            cleanupRequest
        )
    }
}
