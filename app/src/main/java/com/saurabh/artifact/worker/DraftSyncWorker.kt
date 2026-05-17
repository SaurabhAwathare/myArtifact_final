package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.SyncState
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.util.ConnectivityObserver
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class DraftSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val draftDao: DraftDao,
    private val artifactRepository: ArtifactRepository,
    private val authRepository: AuthRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val uploadGuard: com.saurabh.artifact.security.UploadGuard
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val draftId = inputData.getString("draft_id") ?: return Result.failure()
        val userId = authRepository.currentUser.value?.uid ?: return Result.failure()
        
        // 1. Connectivity Check
        if (!connectivityObserver.isOnline()) {
            return Result.retry()
        }

        val draft = draftDao.getDraftById(draftId) ?: return Result.failure()

        // 2. Security Validation (Approval Gate)
        if (!uploadGuard.validateApproval(draft, userId)) {
            Log.e("DraftSyncWorker", "Unauthorized upload attempt for draft $draftId")
            draftDao.update(draft.copy(syncState = SyncState.FAILED_PERMANENT))
            return Result.failure(workDataOf("error" to "Approval validation failed"))
        }

        if (draft.syncState == SyncState.SYNCED) return Result.success()

        // 3. Integrity Validation
        val currentChecksum = artifactRepository.calculateChecksum(draft.localAudioPath)
        if (draft.checksum != null && draft.checksum != currentChecksum) {
            draftDao.update(draft.copy(syncState = SyncState.FAILED_PERMANENT))
            return Result.failure(workDataOf("error" to "Integrity check failed"))
        }

        draftDao.update(draft.copy(
            syncState = SyncState.UPLOADING,
            updatedAt = System.currentTimeMillis()
        ))

        return try {
            val user = authRepository.userData.value
            val userId = authRepository.currentUser.value?.uid ?: "Anonymous"

            // 3. Resumable/Chunked Upload
            val uploadResult = artifactRepository.uploadArtifactResumable(
                userId = userId,
                draft = draft,
                onProgress = { transferred, total, sessionUri ->
                    // Periodically update progress in DB for resilience
                    // Throttle this in production
                    if (transferred % (1024 * 1024) == 0L || transferred == total) {
                        draftDao.updateSyncProgress(
                            draftId = draft.id,
                            uploadedBytes = transferred,
                            totalBytes = total,
                            sessionUri = sessionUri?.toString()
                        )
                    }
                }
            )

            if (uploadResult.isSuccess) {
                val downloadUrl = uploadResult.getOrThrow()
                
                // 4. Create Firestore Document
                val firestoreResult = artifactRepository.createArtifactDocument(
                    userId = userId,
                    username = user?.displayName ?: "Anonymous",
                    audioUrl = downloadUrl,
                    draft = draft,
                    userEmoji = user?.identityEmoji ?: "✨"
                )

                if (firestoreResult.isSuccess) {
                    draftDao.update(draft.copy(
                        syncState = SyncState.SYNCED,
                        updatedAt = System.currentTimeMillis()
                    ))
                    Result.success()
                } else {
                    Result.retry()
                }
            } else {
                val attemptCount = draft.uploadAttemptCount + 1
                draftDao.update(draft.copy(
                    uploadAttemptCount = attemptCount,
                    updatedAt = System.currentTimeMillis()
                ))
                if (attemptCount < 5) Result.retry() else Result.failure()
            }
        } catch (e: Exception) {
            Log.e("DraftSyncWorker", "Sync error for draft $draftId", e)
            Result.retry()
        }
    }
}
