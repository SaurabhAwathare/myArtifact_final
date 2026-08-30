package com.saurabh.artifact.domain

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.StorageException
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.model.AppError
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
    private val uploadGuard: UploadGuard,
    private val diagnosticLogger: DiagnosticLogger
) {

    suspend fun performPublish(
        draftId: String,
        onProgress: suspend (Long, Long, String?) -> Unit = { _, _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        diagnosticLogger.info(DiagnosticCategory.PUBLISH, "PUBLISH_STARTED", mapOf(LogKeys.DRAFT_ID to draftId))
        try {
            val draft = draftRepository.getDraft(draftId).getOrNull() 
                ?: return@withContext Result.failure<Unit>(Exception("Draft not found")).also {
                    diagnosticLogger.error(DiagnosticCategory.PUBLISH, "PUBLISH_FAILED", mapOf(LogKeys.DRAFT_ID to draftId, "reason" to "DRAFT_NOT_FOUND"))
                }
            
            val firebaseUser = FirebaseAuth.getInstance().currentUser 
                ?: return@withContext Result.failure<Unit>(AppError.Unauthenticated()).also {
                    diagnosticLogger.error(DiagnosticCategory.PUBLISH, "PUBLISH_FAILED", mapOf(LogKeys.DRAFT_ID to draftId, "reason" to "UNAUTHENTICATED"))
                }

            // Phase 3: Explicit Ownership Verification
            diagnosticLogger.debug(DiagnosticCategory.PUBLISH, "PUBLISH_OWNERSHIP_CHECK", mapOf(LogKeys.DRAFT_ID to draftId))
            if (draft.userId != firebaseUser.uid) {
                val errorMsg = "Ownership verification failed: Draft belongs to another account."
                diagnosticLogger.error(
                    DiagnosticCategory.PUBLISH, 
                    "PUBLISH_OWNERSHIP_MISMATCH", 
                    mapOf(
                        LogKeys.DRAFT_ID to draftId, 
                        "draftOwner" to draft.userId, 
                        "activeUser" to firebaseUser.uid
                    )
                )
                draftRepository.updateUploadStatus(draftId, SyncStatus.Failed(errorMsg))
                return@withContext Result.failure(AppError.OwnershipMismatch())
            }
            diagnosticLogger.info(DiagnosticCategory.PUBLISH, "PUBLISH_OWNERSHIP_VERIFIED", mapOf(LogKeys.DRAFT_ID to draftId))

            // 1. Security & Integrity Validation
            diagnosticLogger.debug(DiagnosticCategory.PUBLISH, "PUBLISH_STEP_1_VALIDATION", mapOf(LogKeys.DRAFT_ID to draftId))
            if (!uploadGuard.validateApproval(draft, firebaseUser.uid)) {
                val errorMsg = "Security or Integrity validation failed."
                diagnosticLogger.error(DiagnosticCategory.PUBLISH, "PUBLISH_FAILED", mapOf(LogKeys.DRAFT_ID to draftId, "reason" to "VALIDATION_FAILED"))
                draftRepository.updateUploadStatus(draftId, SyncStatus.Failed(errorMsg))
                return@withContext Result.failure(Exception(errorMsg))
            }

            // 2. Check remote status to avoid redundant uploads
            diagnosticLogger.debug(DiagnosticCategory.PUBLISH, "PUBLISH_STEP_2_REMOTE_CHECK", mapOf(LogKeys.DRAFT_ID to draftId))
            val remoteArtifact = artifactRepository.getArtifact(draftId).getOrNull()
            if (remoteArtifact != null && remoteArtifact.status == ArtifactStatus.ACTIVE) {
                diagnosticLogger.info(DiagnosticCategory.PUBLISH, "PUBLISH_ALREADY_ACTIVE", mapOf(LogKeys.DRAFT_ID to draftId))
                withContext(NonCancellable) {
                    draftRepository.markAsPublished(draftId, draftId)
                }
                return@withContext Result.success(Unit)
            }

            if (draft.lifecycle == ArtifactLifecycle.PUBLISHED) {
                diagnosticLogger.info(DiagnosticCategory.PUBLISH, "PUBLISH_ALREADY_LOCAL_PUBLISHED", mapOf(LogKeys.DRAFT_ID to draftId))
                return@withContext Result.success(Unit)
            }

            val audioPath = draft.frozenAudioPath ?: draft.localAudioPath
            draftRepository.updateUploadStatus(draftId, SyncStatus.Uploading)

            // 3. Upload Transcript
            diagnosticLogger.debug(DiagnosticCategory.PUBLISH, "PUBLISH_STEP_3_TRANSCRIPT", mapOf(LogKeys.DRAFT_ID to draftId))
            val transcriptUrl = if (draft.frozenTranscriptJson != null) {
                val uploadResult = artifactRepository.uploadTranscript(
                    userId = firebaseUser.uid,
                    draftId = draft.id,
                    transcriptJson = draft.frozenTranscriptJson.toUnsecureString()
                )
                
                if (uploadResult.isFailure) {
                    val error = uploadResult.exceptionOrNull() ?: Exception("Transcript upload failed")
                    diagnosticLogger.error(
                        DiagnosticCategory.PUBLISH, 
                        "TRANSCRIPT_UPLOAD_STEP_FAILED", 
                        mapOf(
                            LogKeys.DRAFT_ID to draftId,
                            "lifecycle" to draft.lifecycle.name,
                            "publicationStatus" to draft.status.publication.toString()
                        ), 
                        error
                    )
                    
                    // Propagate as a failure to stop the workflow
                    return@withContext Result.failure(error)
                }
                uploadResult.getOrNull()
            } else null

            // 4. Fetch User Profile (Offline-First)
            diagnosticLogger.debug(DiagnosticCategory.PUBLISH, "PUBLISH_STEP_4_PROFILE", mapOf(LogKeys.DRAFT_ID to draftId))
            val userProfile = userRepository.getOrCreateProfile().map { it.user }.getOrElse {
                diagnosticLogger.warn(DiagnosticCategory.PUBLISH, "PUBLISH_PROFILE_FETCH_FAILED_RETRYING_CACHED", mapOf(LogKeys.DRAFT_ID to draftId))
                userRepository.getCachedProfile() ?: throw Exception("User profile not available (even offline)")
            }

            // NEW: Construct Complete AuthorSnapshot (Defense in Depth)
            val authorSnapshot = com.saurabh.artifact.model.AuthorSnapshot.fromUser(userProfile)

            // 5. Pre-register Firestore Document
            diagnosticLogger.debug(DiagnosticCategory.FIRESTORE, "PUBLISH_STEP_5_PRE_REGISTER", mapOf(LogKeys.DRAFT_ID to draftId))
            artifactRepository.createArtifactDocument(
                userId = firebaseUser.uid,
                author = authorSnapshot,
                audioUrl = draft.uploadedAudioUrl ?: "",
                draft = draft,
                identityVersion = userProfile.identityMetadata.identityResetVersion,
                status = if (draft.uploadedAudioUrl != null) ArtifactStatus.ACTIVE else ArtifactStatus.PENDING_UPLOAD,
                isPublic = if (draft.uploadedAudioUrl != null) draft.isPublic else false,
                transcriptUrl = transcriptUrl
            ).getOrThrow()

            // 6. Upload Audio (Resumable)
            diagnosticLogger.debug(DiagnosticCategory.STORAGE, "PUBLISH_STEP_6_AUDIO", mapOf(LogKeys.DRAFT_ID to draftId))
            val downloadUrl = if (draft.uploadedAudioUrl != null) {
                diagnosticLogger.info(DiagnosticCategory.STORAGE, "PUBLISH_AUDIO_CHECKPOINT_REUSE", mapOf(LogKeys.DRAFT_ID to draftId))
                draft.uploadedAudioUrl
            } else {
                val uploadResult = artifactRepository.uploadArtifactResumable(
                    userId = firebaseUser.uid,
                    draft = draft.copy(localAudioPath = audioPath),
                    onProgress = { transferred, total, sessionUri ->
                        diagnosticLogger.debug(DiagnosticCategory.PUBLISH, "UPLOAD_PROGRESS", mapOf(LogKeys.DRAFT_ID to draftId, "transferred" to transferred, "total" to total))
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
            diagnosticLogger.debug(DiagnosticCategory.FIRESTORE, "PUBLISH_STEP_7_FINALIZE", mapOf(LogKeys.DRAFT_ID to draftId))
            artifactRepository.finalizeArtifactDocument(
                artifactId = draftId,
                audioUrl = downloadUrl,
                status = ArtifactStatus.ACTIVE,
                isPublic = draft.isPublic,
                transcriptUrl = transcriptUrl
            ).getOrThrow()

            // 8. Success Cleanup
            diagnosticLogger.debug(DiagnosticCategory.PUBLISH, "PUBLISH_STEP_8_CLEANUP", mapOf(LogKeys.DRAFT_ID to draftId))
            withContext(NonCancellable) {
                draftRepository.markAsPublished(draftId, draftId)
            }

            cleanupManager.scheduleRetentionCleanup(draftId)
            
            // Increment artifactsCount now that it is ACTIVE and public
            userRepository.enqueueArtifactCountIncrement(firebaseUser.uid, draftId)
            
            diagnosticLogger.info(DiagnosticCategory.PUBLISH, "PUBLISH_SUCCESS", mapOf(LogKeys.ARTIFACT_ID to draftId))
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.PUBLISH, "UPLOAD_FAILED", mapOf(LogKeys.DRAFT_ID to draftId, "stage" to getFailureStage(e)), e)
            
            // HARDENING: Ensure draft status is updated to Failed so UI reflects the error
            try {
                draftRepository.updateUploadStatus(draftId, SyncStatus.Failed(e.message ?: "Unknown error"))
            } catch (inner: Exception) {
                diagnosticLogger.warn(DiagnosticCategory.PUBLISH, "FAILED_TO_UPDATE_DRAFT_STATUS", mapOf(LogKeys.DRAFT_ID to draftId), inner)
            }
            
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
