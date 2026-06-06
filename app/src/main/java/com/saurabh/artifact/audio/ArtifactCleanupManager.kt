package com.saurabh.artifact.audio

import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.worker.CleanupWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized manager for orchestrating the "emotional disappearance" of artifacts.
 * Handles optimistic UI state, remote deletion triggers, and local cleanup scheduling.
 */
@Singleton
class ArtifactCleanupManager @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val draftDeletionManager: DraftDeletionManager,
    private val workManager: WorkManager,
) {

    private val _deletingArtifactIds = MutableStateFlow<Set<String>>(emptySet())
    val deletingArtifactIds = _deletingArtifactIds.asStateFlow()

    /**
     * Initiates a resilient deletion flow for a published artifact.
     * 1. Updates state and stops playback if necessary.
     * 2. Triggers remote deletion.
     * 3. Schedules background worker for local file cleanup.
     */
    suspend fun deleteArtifact(artifactId: String): Result<Unit> {
        _deletingArtifactIds.value += artifactId
        return try {
            val result = artifactRepository.deletePublishedArtifact(artifactId)
            
            if (result.isSuccess) {
                Log.d("CleanupManager", "Remote deletion successful for $artifactId. Scheduling local cleanup.")
                scheduleLocalCleanup(artifactId)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                Log.e("CleanupManager", "Remote deletion failed for $artifactId: $errorMsg")
            }
            result
        } finally {
            _deletingArtifactIds.value -= artifactId
        }
    }

    /**
     * Deletes a local draft and its associated files.
     * Triggers reactive UI state (stopping playback) just like a published artifact.
     */
    suspend fun deleteDraft(draftId: String): Result<Unit> {
        _deletingArtifactIds.value += draftId
        return try {
            draftDeletionManager.deleteDraft(draftId)
            Log.d("CleanupManager", "Local draft deletion successful for $draftId")
            Result.success(Unit)
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error"
            Log.e("CleanupManager", "Local draft deletion failed for $draftId: $errorMsg")
            Result.failure(e)
        } finally {
            _deletingArtifactIds.value -= draftId
        }
    }

    /**
     * Schedules a delayed cleanup of local files for a published artifact.
     * This ensures local storage doesn't grow indefinitely.
     */
    fun scheduleRetentionCleanup(artifactId: String) {
        val inputData = Data.Builder()
            .putString(CleanupWorker.KEY_ARTIFACT_ID, artifactId)
            .build()

        val cleanupRequest = OneTimeWorkRequestBuilder<CleanupWorker>()
            .setInitialDelay(30, java.util.concurrent.TimeUnit.DAYS)
            .setInputData(inputData)
            .addTag("retention_cleanup_$artifactId")
            .build()

        workManager.enqueue(cleanupRequest)
    }

    private fun scheduleLocalCleanup(artifactId: String) {
        val inputData = Data.Builder()
            .putString(CleanupWorker.KEY_ARTIFACT_ID, artifactId)
            .build()

        val cleanupRequest = OneTimeWorkRequestBuilder<CleanupWorker>()
            .setInputData(inputData)
            .addTag("cleanup_$artifactId")
            .build()

        workManager.enqueue(cleanupRequest)
    }
}
