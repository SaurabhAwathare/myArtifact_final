package com.saurabh.artifact.repository

import android.net.Uri
import androidx.core.net.toUri
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactConversationMetadata
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.ModerationMetadata
import com.saurabh.artifact.model.ModerationStatus
import com.saurabh.artifact.model.ReactionVisibilityMode
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.model.Visibility
import com.saurabh.artifact.util.NetworkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Handles all active publishing and storage-related functionality.
 * This repository owns the lifecycle of an artifact from local draft to public document.
 */
@Singleton
class ArtifactPublishingRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val draftDao: dagger.Lazy<DraftDao>,
    private val diagnosticLogger: DiagnosticLogger
) {

    suspend fun uploadArtifactResumable(
        userId: String,
        draft: ArtifactDraftEntity,
        onProgress: suspend (Long, Long, Uri?) -> Unit = { _, _, _ -> }
    ): Result<String> = withContext(Dispatchers.IO) {
        val maxRetries = 3
        var currentRetry = 0
        var currentSessionUri = draft.uploadSessionUri

        val originalFile = File(draft.localAudioPath)
        if (!originalFile.exists()) return@withContext Result.failure(Exception("File missing: ${draft.localAudioPath}"))

        if (originalFile.length() == 0L) {
            return@withContext Result.failure(Exception("File is empty, aborting upload"))
        }

        val fileName = "artifacts/${userId}_${draft.id}.m4a"
        val fileRef = storage.reference.child(fileName)

        val metadata = StorageMetadata.Builder()
            .setCustomMetadata("draftId", draft.id)
            .setCustomMetadata("checksum", draft.checksum ?: "")
            .setContentType("audio/x-m4a")
            .build()

        while (currentRetry <= maxRetries) {
            diagnosticLogger.info(
                DiagnosticCategory.STORAGE, 
                "UPLOAD_ATTEMPT", 
                mapOf(LogKeys.DRAFT_ID to draft.id, "retry" to currentRetry, "hasSession" to (currentSessionUri != null))
            )

            try {
                val downloadUrl = withTimeout(5.minutes) {
                    val uploadTask = if (currentSessionUri != null) {
                        fileRef.putFile(originalFile.toUri(), metadata, currentSessionUri.toUri())
                    } else {
                        fileRef.putFile(originalFile.toUri(), metadata)
                    }

                    val taskSnapshot = try {
                        uploadTask.addOnProgressListener { snapshot ->
                            launch {
                                onProgress(snapshot.bytesTransferred, snapshot.totalByteCount, snapshot.uploadSessionUri)
                            }
                        }.await()
                    } catch (e: com.google.firebase.storage.StorageException) {
                        val httpCode = e.httpResultCode
                        // Detect expired or invalid resumable session (404/410)
                        if (currentSessionUri != null && (httpCode == 404 || httpCode == 410)) {
                            diagnosticLogger.warn(
                                DiagnosticCategory.STORAGE, 
                                "UPLOAD_SESSION_EXPIRED", 
                                mapOf(LogKeys.DRAFT_ID to draft.id, "httpCode" to httpCode)
                            )
                            // Clear session in DB and local state
                            draftDao.get().updateSyncProgress(draft.id, draft.userId, 0, draft.totalBytes, null)
                            currentSessionUri = null
                            
                            // Throw transient error to trigger restart from scratch in next loop iteration
                            throw Exception("Resumable session expired, restarting upload")
                        } else {
                            throw e
                        }
                    }

                    // HARDENING: Retrieve downloadUrl from snapshot storage reference for better reliability
                    retryDownloadUrlFetch(taskSnapshot.storage)
                        ?: throw Exception("Upload succeeded but URL retrieval timed out.")
                }

                return@withContext Result.success(downloadUrl)

            } catch (e: Exception) {
                // Phase 8: Reliability - Ensure cancellation is never swallowed
                if (e is CancellationException) throw e

                val isSessionExpired = e.message?.contains("Resumable session expired") == true
                
                if (!isTransientError(e) && !isSessionExpired) {
                    diagnosticLogger.error(
                        DiagnosticCategory.STORAGE, 
                        "UPLOAD_FAILED_TERMINAL", 
                        mapOf(LogKeys.DRAFT_ID to draft.id), 
                        e
                    )
                    return@withContext Result.failure(e)
                } else {
                    currentRetry++
                    if (currentRetry > maxRetries) {
                        diagnosticLogger.error(
                            DiagnosticCategory.STORAGE, 
                            "UPLOAD_FAILED_MAX_RETRIES", 
                            mapOf(LogKeys.DRAFT_ID to draft.id), 
                            e
                        )
                        return@withContext Result.failure(e)
                    } else {
                        val delayTime = (2.0.pow(currentRetry.toDouble()).toLong() * 1000L)
                        diagnosticLogger.warn(
                            DiagnosticCategory.STORAGE, 
                            "UPLOAD_RETRYING", 
                            mapOf(
                                LogKeys.DRAFT_ID to draft.id, 
                                "retry" to currentRetry, 
                                "delayMs" to delayTime,
                                "reason" to (e.message ?: "Transient error")
                            )
                        )
                        // If session expired, we don't necessarily need a long delay as we are starting fresh
                        val effectiveDelay = if (isSessionExpired) 500L else delayTime
                        delay(effectiveDelay.milliseconds)
                        // Continue to next attempt
                    }
                }
            }
        }
        Result.failure(Exception("Upload failed after $maxRetries retries"))
    }

    /**
     * Determines if an error is transient (retriable) or terminal.
     */
    fun isTransientError(e: Throwable): Boolean {
        return NetworkUtils.isTransientError(e)
    }

    /**
     * Hardening: Specifically retries the download URL fetch to handle eventual consistency.
     */
    private suspend fun retryDownloadUrlFetch(ref: com.google.firebase.storage.StorageReference): String? {
        repeat(5) { attempt ->
            try {
                return ref.downloadUrl.await().toString()
            } catch (_: Exception) {
                diagnosticLogger.warn(DiagnosticCategory.STORAGE, "DOWNLOAD_URL_FETCH_RETRYING", mapOf("attempt" to attempt + 1))
                delay((attempt + 1).seconds)
            }
        }
        return null
    }

    suspend fun createArtifactDocument(
        userId: String,
        author: AuthorSnapshot,
        audioUrl: String,
        draft: ArtifactDraftEntity,
        status: ArtifactStatus = ArtifactStatus.ACTIVE,
        isPublic: Boolean = true,
        transcriptUrl: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            // HARDENING: Audit Snapshot before persistence
            diagnosticLogger.debug(DiagnosticCategory.FIRESTORE, "ARTIFACT_DOCUMENT_PRE_REGISTER", mapOf(LogKeys.DRAFT_ID to draft.id))
            
            // 1. Recover Transcript from Frozen Snapshot (Legacy support)
            val transcript = draft.frozenTranscriptJson?.toUnsecureString()?.let { json ->
                try {
                    kotlinx.serialization.json.Json.decodeFromString<List<TranscriptSegment>>(json)
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "TRANSCRIPT_DECODE_FAILED", mapOf(LogKeys.DRAFT_ID to draft.id), e)
                    emptyList()
                }
            } ?: emptyList()

            val artifact = Artifact(
                id = draft.id, // IDEMPOTENCY: Use draftId as the Firestore Document ID
                userId = userId,
                author = author,
                audioUrl = audioUrl,
                createdAt = Timestamp.now(),
                isPublic = isPublic,
                visibility = if (isPublic) Visibility.PUBLIC else Visibility.PRIVATE,
                status = status,
                durationMs = draft.durationMs,
                title = draft.title ?: "Untitled Artifact",
                description = draft.description ?: "",
                emotion = draft.emotion?.label ?: "",
                emotionTag = draft.emotion?.label ?: "",
                prompt = "",
                transcript = transcript,
                transcriptUrl = transcriptUrl,
                amplitudeData = draft.amplitudeData,
                reactionVisibility = draft.reactionVisibility ?: ReactionVisibilityMode.APPROXIMATE,
                conversationMetadata = ArtifactConversationMetadata(
                    primaryStyle = draft.primaryStyle,
                    isAIGenerated = true
                ),
                moderation = ModerationMetadata(
                    status = ModerationStatus.SAFE,
                    updatedAt = Timestamp.now()
                )
            )
            val artifactData = mapArtifactToFirestoreData(artifact)
            
            // 2. Atomic Deterministic Write (Idempotent)
            firestore.runBatch { batch ->
                // A. Public Artifact Entry
                val artifactRef = firestore.collection("artifacts").document(draft.id)
                batch.set(artifactRef, artifactData)

                // B. Private Ownership Record
                val ownershipRef = firestore.collection("users").document(userId)
                    .collection("private").document("published_artifacts")
                    .collection("artifacts").document(draft.id)
                batch.set(ownershipRef, mapOf("createdAt" to Timestamp.now()))
            }.await()
            
            Result.success(draft.id)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "ARTIFACT_DOCUMENT_CREATE_FAILED", mapOf(LogKeys.DRAFT_ID to draft.id), e)
            Result.failure(e)
        }
    }

    suspend fun finalizeArtifactDocument(
        artifactId: String,
        audioUrl: String,
        status: ArtifactStatus,
        isPublic: Boolean,
        transcriptUrl: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val updates = mutableMapOf<String, Any>(
                "audioUrl" to audioUrl,
                "status" to status.name,
                "isDraft" to (status == ArtifactStatus.DRAFT || status == ArtifactStatus.PENDING_UPLOAD),
                "isPublic" to isPublic,
                "visibility" to if (isPublic) Visibility.PUBLIC.name else Visibility.PRIVATE.name
            )
            transcriptUrl?.let { updates["transcriptUrl"] = it }

            firestore.collection("artifacts").document(artifactId)
                .update(updates).await()
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "ARTIFACT_DOCUMENT_FINALIZE_FAILED", mapOf(LogKeys.ARTIFACT_ID to artifactId), e)
            Result.failure(e)
        }
    }

    private fun mapArtifactToFirestoreData(artifact: Artifact): Map<String, Any?> {
        val data = mutableMapOf<String, Any?>(
            "userId" to artifact.userId,
            "author" to mapOf(
                "anonymousId" to artifact.author.anonymousId,
                "name" to artifact.author.name,
                "sigil" to artifact.author.sigil,
                "sigilSeed" to artifact.author.sigilSeed,
                "sigilColor" to artifact.author.sigilColor,
                "sigilConfig" to artifact.author.sigilConfig
            ),
            "audioUrl" to artifact.audioUrl,
            "createdAt" to artifact.createdAt,
            "isPublic" to artifact.isPublic,
            "visibility" to artifact.visibility.name,
            "status" to artifact.status.name,
            "isDraft" to (artifact.status == ArtifactStatus.DRAFT || artifact.status == ArtifactStatus.PENDING_UPLOAD),
            "durationMs" to artifact.durationMs,
            "title" to artifact.title,
            "description" to artifact.description,
            "emotion" to artifact.emotion,
            "emotionTag" to artifact.emotionTag,
            "emotionConfidence" to artifact.emotionConfidence,
            "prompt" to artifact.prompt,
            "reactionVisibility" to artifact.reactionVisibility.name,
            "amplitudeData" to artifact.amplitudeData,
            "moderation" to mapOf(
                "status" to artifact.moderation.status.name,
                "score" to artifact.moderation.score,
                "updatedAt" to artifact.moderation.updatedAt
            ),
            "playCount" to artifact.playCount,
            "commentCount" to 0L,
            "reactionCount" to artifact.reactionCount,
            "reportCount" to artifact.reportCount,
            "safetyConcernCount" to 0L,
            "conversationMetadata" to mapOf(
                "primaryStyle" to artifact.conversationMetadata.primaryStyle?.name,
                "isAIGenerated" to artifact.conversationMetadata.isAIGenerated
            )
        )

        artifact.transcriptUrl?.let { data["transcriptUrl"] = it }

        return data
    }
}
