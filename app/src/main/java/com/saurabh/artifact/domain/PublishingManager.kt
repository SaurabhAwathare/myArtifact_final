package com.saurabh.artifact.domain

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
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
import com.saurabh.artifact.data.local.UploadOwner
import com.saurabh.artifact.model.AuthorSnapshot
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
        expectedOwner: UploadOwner,
        onProgress: suspend (Long, Long, String?) -> Unit = { _, _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        diagnosticLogger.info(DiagnosticCategory.PUBLISH, "PUBLISH_STARTED", mapOf(LogKeys.DRAFT_ID to draftId, "owner" to expectedOwner.name))
        try {
            val draft = draftRepository.getDraft(draftId).getOrNull() 
                ?: return@withContext Result.failure<Unit>(Exception("Draft not found")).also {
                    diagnosticLogger.error(DiagnosticCategory.PUBLISH, "PUBLISH_FAILED", mapOf(LogKeys.DRAFT_ID to draftId, "reason" to "DRAFT_NOT_FOUND"))
                }
            
            val firebaseUser = FirebaseAuth.getInstance().currentUser 
                ?: return@withContext Result.failure<Unit>(AppError.Unauthenticated()).also {
                    diagnosticLogger.error(DiagnosticCategory.PUBLISH, "PUBLISH_FAILED", mapOf(LogKeys.DRAFT_ID to draftId, "reason" to "UNAUTHENTICATED"))
                }

            // Phase 3.5: Authoritative Ownership Re-validation (Concurrency Defense)
            // Ensures that this execution path still authoritatively owns the lock in the database.
            val currentTask = draftRepository.getUploadTask(draftId)
            if (currentTask?.owner != expectedOwner) {
                val errorMsg = "Ownership lost to another process (Current: ${currentTask?.owner}, Expected: $expectedOwner)"
                diagnosticLogger.error(
                    DiagnosticCategory.PUBLISH, 
                    "PUBLISH_OWNERSHIP_LOST", 
                    mapOf(LogKeys.DRAFT_ID to draftId, "reason" to errorMsg)
                )
                return@withContext Result.failure(Exception(errorMsg))
            }

            // Phase 3: Explicit User-Identity Verification
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

            // 3. Prepare Publication Reservation (Server-Authoritative)
            diagnosticLogger.debug(DiagnosticCategory.FIRESTORE, "PUBLISH_STEP_3_PREPARE", mapOf(LogKeys.DRAFT_ID to draftId))
            artifactRepository.preparePublish(draftId).getOrThrow()

            // Reload draft to obtain updated server-authoritative fields like episodeNumber
            val updatedDraft = draftRepository.getDraft(draftId).getOrNull() ?: draft

            // 4. Upload Audio (Resumable)
            diagnosticLogger.debug(DiagnosticCategory.STORAGE, "PUBLISH_STEP_4_AUDIO", mapOf(LogKeys.DRAFT_ID to draftId))
            val downloadUrl = if (updatedDraft.uploadedAudioUrl != null) {
                diagnosticLogger.info(DiagnosticCategory.STORAGE, "PUBLISH_AUDIO_CHECKPOINT_REUSE", mapOf(LogKeys.DRAFT_ID to draftId))
                updatedDraft.uploadedAudioUrl
            } else {
                val uploadResult = artifactRepository.uploadArtifactResumable(
                    userId = firebaseUser.uid,
                    draft = updatedDraft.copy(localAudioPath = audioPath),
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

            // 5. Upload Transcript (if present)
            diagnosticLogger.debug(DiagnosticCategory.PUBLISH, "PUBLISH_STEP_5_TRANSCRIPT", mapOf(LogKeys.DRAFT_ID to draftId))
            val transcriptUrl = if (updatedDraft.frozenTranscriptJson != null) {
                val uploadResult = artifactRepository.uploadTranscript(
                    userId = firebaseUser.uid,
                    draftId = updatedDraft.id,
                    transcriptJson = updatedDraft.frozenTranscriptJson.toUnsecureString()
                )
                
                if (uploadResult.isFailure) {
                    val error = uploadResult.exceptionOrNull() ?: Exception("Transcript upload failed")
                    diagnosticLogger.error(
                        DiagnosticCategory.PUBLISH, 
                        "TRANSCRIPT_UPLOAD_STEP_FAILED", 
                        mapOf(
                            LogKeys.DRAFT_ID to draftId,
                            "lifecycle" to updatedDraft.lifecycle.name,
                            "publicationStatus" to updatedDraft.status.publication.toString()
                        ), 
                        error
                    )
                    return@withContext Result.failure(error)
                }
                uploadResult.getOrNull()
            } else null

            draftRepository.updateUploadStatus(draftId, SyncStatus.Finalizing)

            // 6. Finalize Firestore Document (Server-Authoritative)
            diagnosticLogger.debug(DiagnosticCategory.FIRESTORE, "PUBLISH_STEP_6_FINALIZE", mapOf(LogKeys.DRAFT_ID to draftId, "audioUrl" to downloadUrl, "transcriptUrl" to (transcriptUrl ?: "none")))
            artifactRepository.finalizePublish(draft = updatedDraft).getOrThrow()

            // 7. Success Cleanup
            diagnosticLogger.debug(DiagnosticCategory.PUBLISH, "PUBLISH_STEP_7_CLEANUP", mapOf(LogKeys.DRAFT_ID to draftId))
            withContext(NonCancellable) {
                draftRepository.markAsPublished(draftId, draftId)
            }

            cleanupManager.scheduleRetentionCleanup(draftId)
            
            // Increment artifactsCount now that it is ACTIVE and public
            userRepository.enqueueArtifactCountIncrement(firebaseUser.uid, draftId)
            
            diagnosticLogger.info(DiagnosticCategory.PUBLISH, "PUBLISH_SUCCESS", mapOf(LogKeys.ARTIFACT_ID to draftId))
            android.util.Log.e("PHASE32_VERIFY", "STEP 8 PUBLISH COMPLETED END-TO-END: SUCCEEDED")
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
        if (e is AppError.Unknown) {
            return isPermanentError(e.original)
        }

        when (e) {
            is AppError.Unauthenticated,
            is AppError.OwnershipMismatch,
            is AppError.PermissionDenied,
            is AppError.InvalidInput,
            is AppError.UserNotFound,
            is AppError.NotFound,
            is AppError.ReauthenticationRequired -> return true
            is AppError.NetworkFailure -> return false
            else -> {}
        }

        if (e is IllegalStateException || e is IllegalArgumentException) {
            val cause = e.cause
            if (cause != null && isNetworkError(cause)) {
                return false
            }
            return true
        }

        if (e is StorageException) {
            return when (e.errorCode) {
                StorageException.ERROR_NOT_AUTHORIZED,
                StorageException.ERROR_OBJECT_NOT_FOUND,
                StorageException.ERROR_QUOTA_EXCEEDED -> true
                else -> false
            }
        }

        if (e is FirebaseFirestoreException) {
            return when (e.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                FirebaseFirestoreException.Code.UNAUTHENTICATED,
                FirebaseFirestoreException.Code.INVALID_ARGUMENT,
                FirebaseFirestoreException.Code.NOT_FOUND,
                FirebaseFirestoreException.Code.ALREADY_EXISTS,
                FirebaseFirestoreException.Code.FAILED_PRECONDITION -> true
                else -> false
            }
        }

        val message = e.message ?: ""
        if (message.contains("Security or Integrity validation failed")) {
            return true
        }

        return false
    }

    fun isNetworkError(e: Throwable): Boolean {
        if (e is AppError.Unknown) {
            return isNetworkError(e.original)
        }
        if (e is AppError.NetworkFailure) {
            return true
        }
        if (e is IOException) {
            return true
        }
        if (e is FirebaseNetworkException) {
            return true
        }
        if (e is StorageException && e.errorCode == StorageException.ERROR_RETRY_LIMIT_EXCEEDED) {
            return true
        }
        if (e is FirebaseFirestoreException &&
            e.code == FirebaseFirestoreException.Code.UNAVAILABLE) {
            return true
        }
        return false
    }
}
