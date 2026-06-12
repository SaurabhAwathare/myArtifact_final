package com.saurabh.artifact.domain

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.StorageException
import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.SyncStatus
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.DraftRepository
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.security.UploadGuard
import com.saurabh.artifact.util.NotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PublishingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val draftRepository: DraftRepository,
    private val artifactRepository: ArtifactRepository,
    private val userRepository: UserRepository,
    private val cleanupManager: ArtifactCleanupManager,
    private val uploadGuard: UploadGuard
) {

    suspend fun performPublish(
        draftId: String,
        onProgress: suspend (Long, Long, String?) -> Unit = { _, _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val draft = draftRepository.getDraft(draftId).getOrNull() 
                ?: return@withContext Result.failure(Exception("Draft not found"))
            
            val firebaseUser = FirebaseAuth.getInstance().currentUser 
                ?: return@withContext Result.failure(Exception("User not authenticated"))

            // 1. Security & Integrity Validation
            if (!uploadGuard.validateApproval(draft, firebaseUser.uid)) {
                Log.e("PublishingManager", "Security or Integrity validation failed for draft $draftId")
                draftRepository.updateUploadStatus(draftId, SyncStatus.Failed("Security/Integrity validation failed"))
                return@withContext Result.failure(Exception("Security/Integrity validation failed"))
            }

            // 2. Check remote status to avoid redundant uploads
            val remoteArtifact = artifactRepository.getArtifact(draftId).getOrNull()
            if (remoteArtifact != null && remoteArtifact.status == ArtifactStatus.ACTIVE) {
                Log.i("PublishingManager", "Artifact $draftId is already ACTIVE on Firestore. Syncing local state.")
                withContext(NonCancellable) {
                    draftRepository.markAsPublished(draftId, draftId)
                }
                return@withContext Result.success(Unit)
            }

            if (draft.status.lifecycle == ArtifactLifecycle.PUBLISHED) {
                return@withContext Result.success(Unit)
            }

            val audioPath = draft.frozenAudioPath ?: draft.localAudioPath
            draftRepository.updateUploadStatus(draftId, SyncStatus.Uploading)

            // 3. Upload Transcript
            val transcriptUrl = if (draft.frozenTranscriptJson != null) {
                artifactRepository.uploadTranscript(
                    userId = firebaseUser.uid,
                    draftId = draft.id,
                    transcriptJson = draft.frozenTranscriptJson.toUnsecureString()
                ).getOrNull()
            } else null

            // 4. Fetch User Profile (Offline-First)
            val userProfile = userRepository.getOrCreateProfile().getOrElse {
                Log.w("PublishingManager", "Network profile fetch failed, attempting to use cached profile")
                userRepository.getCachedProfile() ?: throw Exception("User profile not available (even offline)")
            }

            // 5. Pre-register Firestore Document
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

            // 6. Upload Audio (Resumable)
            val downloadUrl = if (draft.uploadedAudioUrl != null) {
                Log.i("PublishingManager", "Using checkpointed audio URL for $draftId")
                draft.uploadedAudioUrl
            } else {
                val uploadResult = artifactRepository.uploadArtifactResumable(
                    userId = firebaseUser.uid,
                    draft = draft.copy(localAudioPath = audioPath),
                    onProgress = { transferred, total, sessionUri ->
                        draftRepository.updateUploadProgress(draftId, transferred, total, sessionUri?.toString())
                        onProgress(transferred, total, sessionUri?.toString())
                    }
                )

                val url = uploadResult.getOrThrow()
                draftRepository.updateUploadedAudioUrl(draftId, url)
                url
            }

            draftRepository.updateUploadStatus(draftId, SyncStatus.Finalizing)

            // 7. Finalize Firestore Document
            artifactRepository.finalizeArtifactDocument(
                artifactId = draftId,
                audioUrl = downloadUrl,
                status = ArtifactStatus.ACTIVE,
                isPublic = draft.isPublic,
                transcriptUrl = transcriptUrl
            ).getOrThrow()

            // 8. Success Cleanup
            withContext(NonCancellable) {
                draftRepository.markAsPublished(draftId, draftId)
            }

            cleanupManager.scheduleRetentionCleanup(draftId)
            
            Log.d("PublishingManager", "Draft $draftId published successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("PublishingManager", "Publishing failed for $draftId", e)
            Result.failure(e)
        }
    }

    fun isPermanentError(e: Throwable): Boolean {
        return e is StorageException && 
            (e.errorCode == StorageException.ERROR_NOT_AUTHORIZED || 
             e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND)
    }

    fun isNetworkError(e: Throwable): Boolean {
        return e is IOException || 
               (e is StorageException && 
                e.errorCode == StorageException.ERROR_RETRY_LIMIT_EXCEEDED)
    }
}
