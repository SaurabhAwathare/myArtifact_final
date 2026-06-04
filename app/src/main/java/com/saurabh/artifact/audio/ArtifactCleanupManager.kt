package com.saurabh.artifact.audio

import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.saurabh.artifact.model.DeletionState
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.worker.CleanupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val workManager: WorkManager
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _deletionState = MutableStateFlow<DeletionState>(DeletionState.Idle)
    val deletionState = _deletionState.asStateFlow()

    private val _deletingArtifactIds = MutableStateFlow<Set<String>>(emptySet())
    val deletingArtifactIds = _deletingArtifactIds.asStateFlow()

    /**
     * Initiates a resilient deletion flow for a published artifact.
     * 1. Updates state and stops playback if necessary.
     * 2. Triggers remote deletion.
     * 3. Schedules background worker for local file cleanup.
     */
    fun deleteArtifact(artifactId: String) {
        scope.launch {
            _deletingArtifactIds.value += artifactId
            _deletionState.value = DeletionState.Pending(artifactId)
            
            val result = artifactRepository.deletePublishedArtifact(artifactId)
            
            if (result.isSuccess) {
                Log.d("CleanupManager", "Remote deletion successful for $artifactId. Scheduling local cleanup.")
                _deletionState.value = DeletionState.RemoteDeleted(artifactId)
                scheduleLocalCleanup(artifactId)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                Log.e("CleanupManager", "Remote deletion failed for $artifactId: $errorMsg")
                _deletionState.value = DeletionState.Error(artifactId, errorMsg)
            }
            
            _deletingArtifactIds.value -= artifactId
        }
    }

    /**
     * Deletes a local draft and its associated files.
     * Triggers reactive UI state (stopping playback) just like a published artifact.
     */
    fun deleteDraft(draftId: String) {
        scope.launch {
            _deletingArtifactIds.value += draftId
            _deletionState.value = DeletionState.Pending(draftId)
            
            try {
                draftDeletionManager.deleteDraft(draftId)
                Log.d("CleanupManager", "Local draft deletion successful for $draftId")
                _deletionState.value = DeletionState.RemoteDeleted(draftId) // Reuse state for consistency
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                Log.e("CleanupManager", "Local draft deletion failed for $draftId: $errorMsg")
                _deletionState.value = DeletionState.Error(draftId, errorMsg)
            } finally {
                _deletingArtifactIds.value -= draftId
            }
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
