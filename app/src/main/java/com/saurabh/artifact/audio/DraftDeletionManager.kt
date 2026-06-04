package com.saurabh.artifact.audio

import android.util.Log
import androidx.room.withTransaction
import androidx.work.*
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.UploadTaskDao
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.util.StorageManager
import com.saurabh.artifact.worker.DeletionWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DraftDeletionManager @Inject constructor(
    private val draftDao: DraftDao,
    private val uploadTaskDao: UploadTaskDao,
    private val draftsDatabase: AppDatabase,
    private val storageManager: StorageManager,
    private val workManager: WorkManager
) {

    /**
     * Authoritative deletion method. 
     * Orchestrates a state-based deletion: DB state update -> File purge -> DB record removal.
     */
    suspend fun deleteDraft(draftId: String) = withContext(Dispatchers.IO) {
        Log.d("DraftDeletionManager", "Initiating deletion for draft: $draftId")

        // 1. Soft Delete: Mark as DELETING in Room. Hides from UI immediately.
        val draft = draftDao.getDraftById(draftId)
        if (draft == null) {
            Log.w("DraftDeletionManager", "Draft $draftId not found in database. Skipping.")
            return@withContext
        }

        if (draft.status.lifecycle != ArtifactLifecycle.DELETING) {
            draftDao.updateStatus(draftId, draft.status.copy(lifecycle = ArtifactLifecycle.DELETING))
        }

        try {
            // 2. Physical File Purge
            val draftDir = storageManager.getDraftDirectory(draftId)
            val success = storageManager.deleteDirectoryRecursively(draftDir)
            
            // Cleanup legacy files if they exist outside the new directory structure
            val legacyFiles = listOfNotNull(
                draft.localAudioPath,
                draft.rawPcmPath,
                draft.localTranscriptPath,
                draft.waveformPath,
                draft.frozenAudioPath
            ).map { File(it) }
            
            legacyFiles.forEach { file ->
                if (file.exists() && !file.absolutePath.startsWith(draftDir.absolutePath)) {
                    storageManager.deleteSecurely(file)
                }
            }

            if (success) {
                // 3. Hard Delete: Success! Remove the DB record.
                draftsDatabase.withTransaction {
                    uploadTaskDao.deleteByDraftId(draftId)
                    draftDao.deleteById(draftId)
                }
                Log.d("DraftDeletionManager", "Successfully deleted draft $draftId and all files.")
            } else {
                throw Exception("Failed to delete directory for $draftId")
            }
        } catch (e: Exception) {
            Log.e("DraftDeletionManager", "File deletion failed for $draftId. Scheduling retry.", e)
            // 4. Recovery: Schedule background retry if file delete fails
            enqueueDeletionRetry(draftId)
        }
    }

    private fun enqueueDeletionRetry(draftId: String) {
        val inputData = workDataOf(DeletionWorker.KEY_DRAFT_ID to draftId)
        val retryWork = OneTimeWorkRequestBuilder<DeletionWorker>()
            .setInputData(inputData)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .build()

        workManager.enqueueUniqueWork(
            "delete_$draftId",
            ExistingWorkPolicy.REPLACE,
            retryWork
        )
    }
}
