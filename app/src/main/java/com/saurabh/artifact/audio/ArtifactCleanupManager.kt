package com.saurabh.artifact.audio

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.worker.CleanupWorker
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
    private val artifactRepository: ArtifactRepository
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val workManager = WorkManager.getInstance(context)

    private val _deletingArtifactIds = MutableStateFlow<Set<String>>(emptySet())
    val deletingArtifactIds = _deletingArtifactIds.asStateFlow()

    /**
     * Initiates a resilient deletion flow.
     * 1. Optimistically updates UI.
     * 2. Triggers remote deletion.
     * 3. Schedules background worker for local file cleanup.
     */
    fun deleteArtifact(artifactId: String) {
        scope.launch {
            _deletingArtifactIds.value += artifactId
            
            val result = artifactRepository.deletePublishedArtifact(artifactId)
            
            if (result.isSuccess) {
                Log.d("CleanupManager", "Remote deletion successful for $artifactId. Scheduling local cleanup.")
                scheduleLocalCleanup(artifactId)
            } else {
                Log.e("CleanupManager", "Remote deletion failed for $artifactId", result.exceptionOrNull())
                // In a production app, we might show a retry snackbar here
                // but for emotional sensitivity, we often prioritize "disappearing" from UI
            }
            
            // Keep in the deleting set for a moment to ensure UI stays "deleted" 
            // even during synchronization lags.
            _deletingArtifactIds.value -= artifactId
        }
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
