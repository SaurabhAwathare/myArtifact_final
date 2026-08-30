package com.saurabh.artifact.audio

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.UploadTaskDao
import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.model.LocalCleanupStatus
import com.saurabh.artifact.model.SyncStatus
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.UserRepository
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
    private val userRepository: Lazy<UserRepository>,
    private val draftDao: Lazy<DraftDao>,
    private val uploadTaskDao: Lazy<UploadTaskDao>,
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
                    // Recover intent: If it was PENDING and has a remote ID, it was likely a manual delete.
                    // But to be fail-safe, we only purge remote if we are absolutely sure.
                    // Since deleteArtifact() now schedules with purgeRemote=true, 
                    // and WorkManager persists that input, this recovery path is mostly for 
                    // state transitions that didn't even start a worker.
                    val shouldPurgeRemote = draft.remoteArtifactId != null && draft.localCleanupStatus == LocalCleanupStatus.PENDING
                    scheduleLocalCleanup(draft.id, purgeRemote = shouldPurgeRemote)
                }
            } catch (e: Exception) {
                ArtifactLogger.e(DiagnosticCategory.WORKMANAGER, "CLEANUP_RECOVERY_FAILED", throwable = e)
            }
        }
    }

    /**
     * Cleans up any stale temporary decrypted files in the cache directory and upload temp.
     */
    fun cleanStaleTempFiles(cacheDir: File) {
        managerScope.launch {
            try {
                // Fetch all active tasks once to avoid repeated DB hits in the loop
                val activeTasks = uploadTaskDao.get().getAllTasks().associateBy { it.draftId }
                
                val targets = listOf(
                    cacheDir, // Legacy Root Cache
                    File(cacheDir, "upload_temp") // New Target Location
                )

                targets.forEach { dir ->
                    if (!dir.exists()) return@forEach
                    
                    dir.listFiles()?.forEach { file ->
                        if (file.name.startsWith("decrypted_") && file.name.endsWith(".m4a")) {
                            // Deterministic naming: decrypted_${draftId}.m4a
                            val draftId = file.name.substringAfter("decrypted_").substringBefore(".m4a")
                            val task = activeTasks[draftId]
                            
                            val shouldDelete = when {
                                task == null -> true // Orphan: No record of this upload task
                                task.status is SyncStatus.Failed -> true // Permanent failure: Cleanup cleartext source
                                // If task hasn't been updated in 12 hours, assume it's a zombie copy
                                System.currentTimeMillis() - task.lastUpdated > 12 * 60 * 60 * 1000L -> true
                                else -> false // Task is active, queued, or recently updated - PRESERVE for resumability
                            }

                            if (shouldDelete) {
                                val deleted = file.delete()
                                if (deleted) {
                                    ArtifactLogger.d(DiagnosticCategory.STORAGE, "TEMP_FILE_CLEANUP_SUCCESS", mapOf("file_name" to file.name, "parent" to dir.name))
                                }
                            }
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
                
                // Decrement artifactsCount for the user
                userRepository.get().enqueueArtifactCountDecrement(userId, artifactId)
                
                scheduleLocalCleanup(artifactId, purgeRemote = true)
            } else {
                ArtifactLogger.e(DiagnosticCategory.PUBLISH, "ARTIFACT_REMOTE_DELETION_FAILED", mapOf("artifactId" to artifactId))
                // Even if remote failed, we schedule the worker to handle the remote retry + local cleanup
                scheduleLocalCleanup(artifactId, purgeRemote = true)
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
            scheduleLocalCleanup(draftId, purgeRemote = false)
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
            .putBoolean(CleanupWorker.KEY_PURGE_REMOTE, false) // Explicitly safe
            .build()

        val cleanupRequest = OneTimeWorkRequestBuilder<CleanupWorker>()
            .setInitialDelay(RetentionPolicy.DEFAULT_RETENTION_DAYS, RetentionPolicy.RETENTION_TIME_UNIT)
            .setInputData(inputData)
            .addTag("retention_cleanup_$artifactId")
            .addTag(com.saurabh.artifact.domain.auth.SessionConstants.TAG_USER_SESSION_WORK)
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

    private fun scheduleLocalCleanup(artifactId: String, purgeRemote: Boolean) {
        val inputData = Data.Builder()
            .putString(CleanupWorker.KEY_ARTIFACT_ID, artifactId)
            .putBoolean(CleanupWorker.KEY_PURGE_REMOTE, purgeRemote)
            .build()

        val cleanupRequest = OneTimeWorkRequestBuilder<CleanupWorker>()
            .setInputData(inputData)
            .addTag("cleanup_$artifactId")
            .addTag(com.saurabh.artifact.domain.auth.SessionConstants.TAG_USER_SESSION_WORK)
            .build()

        workManager.enqueueUniqueWork(
            "cleanup_$artifactId",
            ExistingWorkPolicy.REPLACE,
            cleanupRequest
        )
    }
}
