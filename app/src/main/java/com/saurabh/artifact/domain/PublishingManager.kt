package com.saurabh.artifact.domain

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PublishingManager @Inject constructor(
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
        Log.i("PublishingManager", "Starting publishing flow for draft $draftId")
        try {
            val draft = draftRepository.getDraft(draftId).getOrNull() 
                ?: return@withContext Result.failure<Unit>(Exception("Draft not found")).also {
                    Log.e("PublishingManager", "Draft $draftId not found in repository")
                }
            
            val firebaseUser = FirebaseAuth.getInstance().currentUser 
                ?: return@withContext Result.failure<Unit>(Exception("User not authenticated")).also {
                    Log.e("PublishingManager", "No authenticated user found for draft $draftId")
                }

            // 1. Security & Integrity Validation
            Log.d("PublishingManager", "Step 1: Validating approval and integrity for $draftId")
            if (!uploadGuard.validateApproval(draft, firebaseUser.uid)) {
                val errorMsg = "Security or Integrity validation failed for draft $draftId. Token or Timestamp mismatch."
                Log.e("PublishingManager", errorMsg)
                draftRepository.updateUploadStatus(draftId, SyncStatus.Failed(errorMsg))
                return@withContext Result.failure(Exception(errorMsg))
            }

            // 2. Check remote status to avoid redundant uploads
            Log.d("PublishingManager", "Step 2: Checking remote status for $draftId")
            val remoteArtifact = artifactRepository.getArtifact(draftId).getOrNull()
            if (remoteArtifact != null && remoteArtifact.status == ArtifactStatus.ACTIVE) {
                Log.i("PublishingManager", "Artifact $draftId is already ACTIVE on Firestore. Syncing local state.")
                withContext(NonCancellable) {
                    draftRepository.markAsPublished(draftId, draftId)
                }
                return@withContext Result.success(Unit)
            }

            if (draft.lifecycle == ArtifactLifecycle.PUBLISHED) {
                Log.i("PublishingManager", "Draft $draftId already marked as PUBLISHED locally.")
                return@withContext Result.success(Unit)
            }

            val audioPath = draft.frozenAudioPath ?: draft.localAudioPath
            draftRepository.updateUploadStatus(draftId, SyncStatus.Uploading)

            // 3. Upload Transcript
            Log.d("PublishingManager", "Step 3: Uploading transcript for $draftId")
            val transcriptUrl = if (draft.frozenTranscriptJson != null) {
                artifactRepository.uploadTranscript(
                    userId = firebaseUser.uid,
                    draftId = draft.id,
                    transcriptJson = draft.frozenTranscriptJson.toUnsecureString()
                ).getOrNull()
            } else null

            // 4. Fetch User Profile (Offline-First)
            Log.d("PublishingManager", "Step 4: Fetching user profile for $draftId")
            val userProfile = userRepository.getOrCreateProfile().map { it.user }.getOrElse {
                Log.w("PublishingManager", "Network profile fetch failed, attempting to use cached profile")
                userRepository.getCachedProfile() ?: throw Exception("User profile not available (even offline)")
            }

            // NEW: Construct Complete AuthorSnapshot (Defense in Depth)
            val authorSnapshot = com.saurabh.artifact.model.AuthorSnapshot.fromUser(userProfile)

            // 5. Pre-register Firestore Document
            Log.d("PublishingManager", "Step 5: Pre-registering Firestore document for $draftId")
            artifactRepository.createArtifactDocument(
                userId = firebaseUser.uid,
                author = authorSnapshot,
                audioUrl = draft.uploadedAudioUrl ?: "",
                draft = draft,
                status = if (draft.uploadedAudioUrl != null) ArtifactStatus.ACTIVE else ArtifactStatus.PENDING_UPLOAD,
                isPublic = if (draft.uploadedAudioUrl != null) draft.isPublic else false,
                transcriptUrl = transcriptUrl
            ).getOrThrow()

            // 6. Upload Audio (Resumable)
            Log.d("PublishingManager", "Step 6: Starting audio upload for $draftId")
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
            Log.d("PublishingManager", "Step 7: Finalizing Firestore document for $draftId")
            artifactRepository.finalizeArtifactDocument(
                artifactId = draftId,
                audioUrl = downloadUrl,
                status = ArtifactStatus.ACTIVE,
                isPublic = draft.isPublic,
                transcriptUrl = transcriptUrl
            ).getOrThrow()

            // 8. Success Cleanup
            Log.d("PublishingManager", "Step 8: Cleanup and marking as published for $draftId")
            withContext(NonCancellable) {
                draftRepository.markAsPublished(draftId, draftId)
            }

            cleanupManager.scheduleRetentionCleanup(draftId)
            
            Log.i("PublishingManager", "Draft $draftId published successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("PublishingManager", "Publishing flow failed for $draftId at stage ${getFailureStage(e)}", e)
            Result.failure(e)
        }
    }

    private fun getFailureStage(e: Exception): String {
        val stackTrace = e.stackTrace.firstOrNull { it.className == this::class.java.name }
        return stackTrace?.lineNumber?.toString() ?: "unknown"
    }

    fun isPermanentError(e: Throwable): Boolean {
        val message = e.message ?: ""
        return (e is StorageException && 
            (e.errorCode == StorageException.ERROR_NOT_AUTHORIZED || 
             e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND)) ||
             message.contains("Security or Integrity validation failed")
    }

    fun isNetworkError(e: Throwable): Boolean {
        return e is IOException || 
               (e is StorageException && 
                e.errorCode == StorageException.ERROR_RETRY_LIMIT_EXCEEDED)
    }
}
