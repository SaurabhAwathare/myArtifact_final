package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.SyncStatus
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.DraftRepository
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.security.UploadGuard
import com.saurabh.artifact.util.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.google.firebase.storage.StorageException
import java.io.IOException

@HiltWorker
class PublishingWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val draftRepository: DraftRepository,
    private val artifactRepository: ArtifactRepository,
    private val userRepository: UserRepository,
    private val cleanupManager: ArtifactCleanupManager,
    private val uploadGuard: UploadGuard
) : CoroutineWorker(appContext, workerParams) {

    private var startTime = System.currentTimeMillis()
    private var lastProgressUpdateTime = 0L

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        val draft = draftRepository.getDraft(draftId) ?: return@withContext Result.failure()
        
        startTime = System.currentTimeMillis()

        // 0. Initialize Foreground Service for long-running upload
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w("PublishingWorker", "Could not set foreground info", e)
        }

        // 1. Authentication check
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        if (firebaseUser == null) {
            draftRepository.updateUploadStatus(draftId, SyncStatus.Failed("User not authenticated"))
            return@withContext Result.failure()
        }

        // 2. Security Validation (Approval Gate)
        if (!uploadGuard.validateApproval(draft, firebaseUser.uid)) {
            Log.e("PublishingWorker", "Unauthorized upload attempt for draft $draftId")
            draftRepository.updateUploadStatus(draftId, SyncStatus.Failed("Approval validation failed", recoverable = false))
            return@withContext Result.failure()
        }

        // 3. Integrity Validation
        val currentChecksum = artifactRepository.calculateChecksum(draft.localAudioPath)
        if (draft.checksum != null && draft.checksum != currentChecksum) {
            Log.e("PublishingWorker", "Integrity check failed for draft $draftId")
            draftRepository.updateUploadStatus(draftId, SyncStatus.Failed("Integrity check failed", recoverable = false))
            return@withContext Result.failure()
        }

        // 0.5. Check remote status to avoid redundant uploads if already published
        // If the process previously succeeded on the server but crashed before updating local DB
        val remoteArtifact = artifactRepository.getArtifact(draftId).getOrNull()
        if (remoteArtifact != null && remoteArtifact.status == ArtifactStatus.ACTIVE) {
            Log.i("PublishingWorker", "Artifact $draftId is already ACTIVE on Firestore. Syncing local state.")
            withContext(NonCancellable) {
                draftRepository.markAsPublished(draftId, draftId)
            }
            return@withContext Result.success()
        }

        // 1. Check if already published to prevent duplicates
        if (draft.status.lifecycle == ArtifactLifecycle.PUBLISHED) {
            return@withContext Result.success()
        }

        // 2. Use Frozen Snapshot if available
        val audioPath = draft.frozenAudioPath ?: draft.localAudioPath
        
        try {
            // 4. Update state to UPLOADING
            draftRepository.updateUploadStatus(draftId, SyncStatus.Uploading(0f))

            // 4.2 Upload Transcript (New)
            val transcriptUrl = if (draft.frozenTranscriptJson != null) {
                artifactRepository.uploadTranscript(
                    userId = firebaseUser.uid,
                    draftId = draft.id,
                    transcriptJson = draft.frozenTranscriptJson
                ).getOrNull()
            } else null

            // 4.5 Fetch Anonymous Identity from Firestore
            val userProfile = userRepository.getOrCreateProfile()

            // 5. Pre-register Firestore Document (Checkpoint)
            // Note: Since we use draftId as artifactId, createArtifactDocument is idempotent
            artifactRepository.createArtifactDocument(
                userId = firebaseUser.uid,
                username = userProfile.anonymousName,
                audioUrl = draft.uploadedAudioUrl ?: "",
                draft = draft,
                avatarSeed = userProfile.avatarSeed,
                avatarColor = userProfile.avatarColor,
                avatarConfig = userProfile.avatarConfig,
                anonymousId = userProfile.anonymousId,
                status = if (draft.uploadedAudioUrl != null) ArtifactStatus.ACTIVE else ArtifactStatus.PENDING_UPLOAD,
                isPublic = if (draft.uploadedAudioUrl != null) draft.isPublic else false,
                transcriptUrl = transcriptUrl
            ).getOrThrow()

            // 6. Check if Audio is already uploaded (Checkpoint)
            val downloadUrl = if (draft.uploadedAudioUrl != null) {
                Log.i("PublishingWorker", "Using checkpointed audio URL for $draftId")
                draft.uploadedAudioUrl
            } else {
                // Upload Audio (Resumable)
                val uploadResult = artifactRepository.uploadArtifactResumable(
                    userId = firebaseUser.uid,
                    draft = draft.copy(localAudioPath = audioPath), // Use frozen path
                    onProgress = { transferred, total, sessionUri ->
                        val now = System.currentTimeMillis()
                        // Throttle updates to every 500ms, but always allow 100% completion
                        if (now - lastProgressUpdateTime > 500L || transferred == total) {
                            lastProgressUpdateTime = now
                            draftRepository.updateUploadProgress(draftId, transferred, total, sessionUri?.toString())
                            updateNotificationIfNeeded(draft.title ?: "Artifact", transferred, total)
                        }
                    }
                )

                val url = uploadResult.getOrThrow()
                // HARDENING: Persist URL immediately to prevent re-upload on worker crash
                draftRepository.updateUploadedAudioUrl(draftId, url)
                url
            }

            // 7. Update state to FINALIZING (Atomic Firestore update coming up)
            draftRepository.updateUploadStatus(draftId, SyncStatus.Finalizing)

            // 8. Finalize Firestore Document
            artifactRepository.finalizeArtifactDocument(
                artifactId = draftId,
                audioUrl = downloadUrl,
                status = ArtifactStatus.ACTIVE,
                isPublic = draft.isPublic,
                transcriptUrl = transcriptUrl
            ).getOrThrow()

            // 9. Success - Atomically update state
            withContext(NonCancellable) {
                draftRepository.markAsPublished(draftId, draftId)
            }

            // 10. Schedule Automatic Cleanup (30 days)
            cleanupManager.scheduleRetentionCleanup(draftId)

            NotificationHelper.showUploadSuccessNotification(appContext, draft.title ?: "Artifact")
            Log.d("PublishingWorker", "Draft $draftId published successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e("PublishingWorker", "Publishing failed for $draftId", e)
            
            // Check if it's a transient failure or permanent
            val isPermanent = e is StorageException && 
                (e.errorCode == StorageException.ERROR_NOT_AUTHORIZED || 
                 e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND)

            withContext(NonCancellable) {
                if (isPermanent) {
                    draftRepository.updateUploadStatus(draftId, SyncStatus.Failed(e.message ?: "Permanent upload failure", recoverable = false))
                    NotificationHelper.showUploadErrorNotification(appContext, draft.title ?: "Artifact")
                } else {
                    // If it's a network error or transient, set to WaitingForNetwork or Queued
                    val isNetworkError = e is IOException || 
                                       (e is StorageException && 
                                        e.errorCode == StorageException.ERROR_RETRY_LIMIT_EXCEEDED)
                    
                    val nextStatus = if (isNetworkError) SyncStatus.WaitingForNetwork else SyncStatus.Queued
                    draftRepository.updateUploadStatus(draftId, nextStatus)
                }
            }

            if (isPermanent) Result.failure() else Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return NotificationHelper.getUploadForegroundInfo(
            appContext,
            "Artifact",
            0
        )
    }

    private fun updateNotificationIfNeeded(title: String, transferred: Long, total: Long) {
        val now = System.currentTimeMillis()
        val duration = now - startTime
        
        // Only show notification if backgrounded (simplified check) or taking long
        if (duration > 5000) {
            val progress = (transferred * 100 / total).toInt()
            NotificationHelper.updateUploadProgress(appContext, title, progress)
        }
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
