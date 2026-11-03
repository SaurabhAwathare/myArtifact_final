package com.saurabh.artifact.repository

import android.content.Context
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.saurabh.artifact.data.local.DeadLetterInteractionDao
import com.saurabh.artifact.data.local.DeadLetterInteractionEntity
import com.saurabh.artifact.data.local.DlqRecoveryResult
import com.saurabh.artifact.data.local.IgnoredUserDao
import com.saurabh.artifact.data.local.InteractionAction
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.data.local.PendingInteractionEntity
import com.saurabh.artifact.data.remote.model.CommentDto
import com.saurabh.artifact.data.remote.model.CommentIntentDto
import com.saurabh.artifact.data.remote.model.CommentPayload
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.Comment
import com.saurabh.artifact.model.CommentStatus
import com.saurabh.artifact.model.toDomain
import com.saurabh.artifact.model.toDto
import com.saurabh.artifact.util.CommentConstants
import com.saurabh.artifact.worker.InteractionSyncWorker
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreCommentRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val firestore: FirebaseFirestore,
    private val ignoredUserDao: Lazy<IgnoredUserDao>,
    private val pendingInteractionDao: Lazy<PendingInteractionDao>,
    private val deadLetterInteractionDao: Lazy<DeadLetterInteractionDao>,
    private val authRepository: AuthRepository,
    private val diagnosticLogger: DiagnosticLogger
) : CommentRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val activeIntentListeners = ConcurrentHashMap<String, ListenerRegistration>()
    private val repositoryScope = CoroutineScope(Dispatchers.IO)

    override suspend fun getComments(
        artifactId: String,
        limit: Int,
        lastVisible: DocumentSnapshot?
    ): Result<PaginatedComments> = withContext(Dispatchers.IO) {
        try {
            var query = firestore.collection(CommentConstants.getCommentsCollectionPath(artifactId))
                .whereIn("status", listOf(CommentStatus.ACTIVE.name, CommentStatus.MODERATED.name))
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())

            lastVisible?.let {
                query = query.startAfter(it)
            }

            val snapshot = query.get().await()
            val currentUserId = authRepository.currentUserId
            val ignoredIds = if (currentUserId.isNotEmpty()) {
                ignoredUserDao.get().getAllIgnoredUserIds(currentUserId).toSet()
            } else emptySet()
            
            val comments = snapshot.documents.mapNotNull { doc ->
                val commentDto = doc.toObject(CommentDto::class.java)
                val authorAnonId = commentDto?.author?.anonymousId?.ifBlank { null }
                    ?: commentDto?.authorAnonymousId?.ifBlank { null }
                    ?: doc.getString("authorAnonymousId")
                
                // R068 FIX: Filter by persona ID consistently
                if (authorAnonId != null && ignoredIds.contains(authorAnonId)) return@mapNotNull null
                
                commentDto?.copy(id = doc.id)?.toDomain()
            }

            val nextLastVisible = if (snapshot.documents.size < limit) {
                null
            } else {
                snapshot.documents.lastOrNull()
            }

            Result.success(PaginatedComments(comments, nextLastVisible))
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.FIRESTORE,
                "COMMENT_FETCH_FAILED",
                mapOf("artifactId" to artifactId),
                e
            )
            Result.failure(AppError.from(e))
        }
    }

    override suspend fun createComment(comment: Comment): Result<Comment> = withContext(Dispatchers.IO) {
        val currentUserId = authRepository.currentUserId
        if (currentUserId.isEmpty()) return@withContext Result.failure(AppError.Unauthenticated())

        try {
            diagnosticLogger.info(
                DiagnosticCategory.COMMENT, 
                "COMMENT_INTENT_SUBMIT_STARTED", 
                mapOf(LogKeys.ARTIFACT_ID to comment.artifactId)
            )
            
            val intentRef = firestore.collection("users").document(currentUserId)
                .collection("private").document("intents")
                .collection("comments").document(if (comment.id.isNotEmpty()) comment.id else UUID.randomUUID().toString())

            val dto = CommentIntentDto.fromDomain(comment)

            intentRef.set(dto).await()
            
            diagnosticLogger.info(
                DiagnosticCategory.COMMENT, 
                "COMMENT_INTENT_SUBMIT_SUCCESS", 
                mapOf(LogKeys.ARTIFACT_ID to comment.artifactId, "commentId" to intentRef.id)
            )
            
            Result.success(comment.copy(id = intentRef.id))
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.COMMENT,
                "COMMENT_INTENT_SUBMIT_FAILED",
                mapOf(LogKeys.ARTIFACT_ID to comment.artifactId),
                e
            )
            Result.failure(AppError.from(e))
        }
    }

    override suspend fun enqueueComment(comment: Comment): Result<Unit> = withContext(Dispatchers.IO) {
        val currentUserId = authRepository.currentUserId
        if (currentUserId.isEmpty()) return@withContext Result.failure(AppError.Unauthenticated())

        try {
            val payload = CommentPayload.fromDomain(comment)
            val commentJson = json.encodeToString(CommentPayload.serializer(), payload)
            val pending = PendingInteractionEntity(
                userId = currentUserId,
                artifactId = comment.artifactId,
                interactionType = InteractionType.COMMENT,
                action = InteractionAction.ADD,
                metadata = commentJson
            )
            
            pendingInteractionDao.get().insert(pending)
            InteractionSyncWorker.enqueue(context)
            
            diagnosticLogger.info(
                DiagnosticCategory.COMMENT, 
                "COMMENT_QUEUED_LOCALLY", 
                mapOf(LogKeys.ARTIFACT_ID to comment.artifactId, "commentId" to comment.id)
            )
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.COMMENT,
                "COMMENT_ENQUEUE_FAILED",
                mapOf(LogKeys.ARTIFACT_ID to comment.artifactId),
                e
            )
            Result.failure(AppError.from(e))
        }
    }

    override suspend fun deleteComment(
        artifactId: String,
        commentId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val docRef = firestore.collection(CommentConstants.getCommentsCollectionPath(artifactId))
                .document(commentId)

            // Soft delete by updating status
            docRef.update("status", CommentStatus.DELETED.name).await()
            
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.FIRESTORE,
                "COMMENT_DELETE_FAILED",
                mapOf("artifactId" to artifactId, "commentId" to commentId),
                e
            )
            Result.failure(AppError.from(e))
        }
    }

    override suspend fun observeIntentStatus(userId: String, commentId: String, artifactId: String) {
        if (userId.isEmpty() || commentId.isEmpty()) return
        
        if (activeIntentListeners.containsKey(commentId)) return

        val currentAuthUid = authRepository.currentUserId
        if (currentAuthUid != userId) return

        val docRef = firestore.collection("users").document(userId)
            .collection("private").document("intents")
            .collection("comments").document(commentId)

        val listenerRegistration = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                diagnosticLogger.warn(
                    DiagnosticCategory.COMMENT,
                    "COMMENT_INTENT_LISTEN_ERROR",
                    mapOf("commentId" to commentId, "error" to (error.message ?: ""))
                )
                return@addSnapshotListener
            }

            if (snapshot == null || !snapshot.exists()) {
                diagnosticLogger.warn(
                    DiagnosticCategory.COMMENT,
                    "COMMENT_INTENT_DOC_MISSING",
                    mapOf("commentId" to commentId)
                )
                return@addSnapshotListener
            }

            val status = snapshot.getString("status") ?: "PENDING"
            
            repositoryScope.launch {
                handleIntentSnapshotUpdate(userId, commentId, artifactId, status, snapshot)
            }
        }

        activeIntentListeners[commentId] = listenerRegistration
    }

    private suspend fun handleIntentSnapshotUpdate(
        userId: String,
        commentId: String,
        artifactId: String,
        status: String,
        snapshot: DocumentSnapshot
    ) {
        if (authRepository.currentUserId != userId) {
            stopIntentObservation(commentId)
            return
        }

        val pendingInteractions = pendingInteractionDao.get().getPendingByType(
            artifactId = artifactId,
            userId = userId,
            type = InteractionType.COMMENT
        )

        val matchingInteraction = pendingInteractions.firstOrNull { interaction ->
            try {
                val payload = json.decodeFromString(CommentPayload.serializer(), interaction.metadata ?: "")
                payload.id == commentId
            } catch (_: Exception) {
                false
            }
        }

        when (status) {
            "PROCESSED" -> {
                diagnosticLogger.info(
                    DiagnosticCategory.COMMENT,
                    "COMMENT_INTENT_PROCESSED_SUCCESS",
                    mapOf("commentId" to commentId, "artifactId" to artifactId)
                )
                stopIntentObservation(commentId)
                if (matchingInteraction != null) {
                    pendingInteractionDao.get().delete(matchingInteraction)
                }
            }

            "RATE_LIMITED" -> {
                val rateLimitResetAt = snapshot.getLong("rateLimitResetAt")
                val lastConsumed = snapshot.getLong("lastConsumedRetryAt")
                val waitTimeMs = snapshot.getLong("waitTimeMs") ?: 0L

                diagnosticLogger.warn(
                    DiagnosticCategory.COMMENT,
                    "COMMENT_INTENT_RATE_LIMITED",
                    mapOf("commentId" to commentId, "resetAt" to (rateLimitResetAt ?: 0L), "waitTimeMs" to waitTimeMs)
                )

                if (matchingInteraction != null) {
                    try {
                        val payload = json.decodeFromString(CommentPayload.serializer(), matchingInteraction.metadata ?: "")
                        val updatedPayload = payload.copy(
                            intentSubmitted = true,
                            intentStatus = "RATE_LIMITED",
                            rateLimitResetAtMs = rateLimitResetAt,
                            lastConsumedRetryAtMs = lastConsumed
                        )
                        val updatedJson = json.encodeToString(CommentPayload.serializer(), updatedPayload)
                        val updatedInteraction = matchingInteraction.copy(
                            metadata = updatedJson,
                            lastError = "RATE_LIMITED: wait $waitTimeMs ms"
                        )
                        pendingInteractionDao.get().insert(updatedInteraction)
                    } catch (e: Exception) {
                        diagnosticLogger.error(
                            DiagnosticCategory.COMMENT,
                            "COMMENT_INTENT_PAYLOAD_UPDATE_FAILED",
                            mapOf("commentId" to commentId),
                            e
                        )
                    }
                }

                if (rateLimitResetAt != null && rateLimitResetAt > 0) {
                    val delayMs = maxOf(0L, rateLimitResetAt - System.currentTimeMillis())
                    InteractionSyncWorker.enqueueDelayed(context, delayMs)
                }
            }

            "REJECTED_NOT_FOUND", "REJECTED_UNAUTHORIZED", "REJECTED_INVALID" -> {
                val rejectionReason = snapshot.getString("rejectionReason") ?: "Comment intent rejected: $status"
                diagnosticLogger.error(
                    DiagnosticCategory.COMMENT,
                    "COMMENT_INTENT_TERMINAL_REJECTION",
                    mapOf("commentId" to commentId, "status" to status, "reason" to rejectionReason),
                    null
                )
                stopIntentObservation(commentId)

                if (matchingInteraction != null) {
                    val dlqEntry = DeadLetterInteractionEntity(
                        userId = userId,
                        originalId = matchingInteraction.id,
                        artifactId = artifactId,
                        interactionType = InteractionType.COMMENT,
                        action = matchingInteraction.action,
                        metadata = matchingInteraction.metadata,
                        createdAt = matchingInteraction.createdAt,
                        correlationId = matchingInteraction.correlationId,
                        failureReason = rejectionReason,
                        failureType = status,
                        retryCount = matchingInteraction.retryCount
                    )
                    deadLetterInteractionDao.get().moveToDeadLetter(dlqEntry, matchingInteraction)
                }
            }

            "PENDING" -> {
                if (matchingInteraction != null) {
                    try {
                        val payload = json.decodeFromString(CommentPayload.serializer(), matchingInteraction.metadata ?: "")
                        if (!payload.intentSubmitted || payload.intentStatus != "PENDING") {
                            val updatedPayload = payload.copy(
                                intentSubmitted = true,
                                intentStatus = "PENDING"
                            )
                            val updatedJson = json.encodeToString(CommentPayload.serializer(), updatedPayload)
                            pendingInteractionDao.get().insert(matchingInteraction.copy(metadata = updatedJson))
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override suspend fun requestCommentRetry(userId: String, commentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (userId.isEmpty() || commentId.isEmpty()) return@withContext Result.failure(AppError.Unauthenticated())

        val currentAuthUid = authRepository.currentUserId
        if (currentAuthUid != userId) return@withContext Result.failure(AppError.Unauthenticated("Auth UID mismatch"))

        try {
            val docRef = firestore.collection("users").document(userId)
                .collection("private").document("intents")
                .collection("comments").document(commentId)

            val snapshot = docRef.get().await()
            if (!snapshot.exists()) {
                return@withContext Result.failure(AppError.NotFound("CommentIntent", commentId))
            }

            val status = snapshot.getString("status")
            if (status != "RATE_LIMITED") {
                diagnosticLogger.info(
                    DiagnosticCategory.COMMENT,
                    "COMMENT_RETRY_SKIPPED_NOT_RATE_LIMITED",
                    mapOf("commentId" to commentId, "status" to (status ?: "null"))
                )
                return@withContext Result.success(Unit)
            }

            val oldClientRetry = snapshot.getLong("clientRetryRequestedAt")
            val lastConsumed = snapshot.getLong("lastConsumedRetryAt")
            val rateLimitResetAt = snapshot.getLong("rateLimitResetAt")

            val retryTimestamp = calculateClientRetryTimestamp(
                rateLimitResetAt = rateLimitResetAt,
                oldClientRetry = oldClientRetry,
                lastConsumed = lastConsumed
            )

            docRef.update("clientRetryRequestedAt", retryTimestamp).await()

            diagnosticLogger.info(
                DiagnosticCategory.COMMENT,
                "COMMENT_RETRY_REQUESTED_SUCCESS",
                mapOf("commentId" to commentId, "retryTimestamp" to retryTimestamp)
            )

            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.COMMENT,
                "COMMENT_RETRY_REQUEST_FAILED",
                mapOf("commentId" to commentId),
                e
            )
            Result.failure(AppError.from(e))
        }
    }

    override suspend fun recoverEligibleDeadLetters(userId: String): Result<Int> = withContext(Dispatchers.IO) {
        if (userId.isEmpty() || authRepository.currentUserId != userId) {
            return@withContext Result.failure(AppError.Unauthenticated())
        }

        try {
            val nowMs = System.currentTimeMillis()
            val maxFailedAt = nowMs - CommentConstants.DLQ_RECOVERY_COOLDOWN_MS
            val deadLetters = deadLetterInteractionDao.get().getEligibleDeadLettersForRecovery(
                userId = userId,
                interactionType = InteractionType.COMMENT,
                maxFailedAt = maxFailedAt
            )
            var recoveredCount = 0

            for (dl in deadLetters) {
                // 1. Interaction type check
                if (dl.interactionType != InteractionType.COMMENT) continue

                // 2. Failure classification check - must be transient retry limit exceeded
                if (dl.failureType != "RETRY_LIMIT_EXCEEDED") continue

                // 3. Recovery cooldown check - failedAt must be at least 15 minutes old
                if (nowMs - dl.failedAt < CommentConstants.DLQ_RECOVERY_COOLDOWN_MS) continue

                // 4. User ID & artifact ID validation
                if (dl.userId.isBlank() || dl.userId != userId || dl.artifactId.isBlank()) continue

                // 5. Payload integrity validation
                val metadata = dl.metadata
                if (metadata.isNullOrBlank()) continue

                val payload = try {
                    json.decodeFromString(CommentPayload.serializer(), metadata)
                } catch (_: Exception) {
                    continue
                }

                if (payload.id.isBlank() || payload.artifactId.isBlank() || payload.text.isBlank()) continue
                if (payload.artifactId != dl.artifactId) continue
                if (payload.author.anonymousId.isBlank()) continue

                // 6. Duplicate check by comment ID in pending_interactions for extra safety
                val existingPending = pendingInteractionDao.get().getPendingForUser(userId)
                val duplicatePending = existingPending.firstOrNull { pending ->
                    if (pending.interactionType != InteractionType.COMMENT) return@firstOrNull false
                    val pendingPayload = try {
                        json.decodeFromString(CommentPayload.serializer(), pending.metadata ?: "")
                    } catch (_: Exception) {
                        null
                    }
                    pendingPayload?.id == payload.id
                }

                if (duplicatePending != null) {
                    // Safe resolution: pending already exists, remove duplicate DLQ entry atomically
                    deadLetterInteractionDao.get().deleteById(dl.id)
                    diagnosticLogger.info(
                        DiagnosticCategory.COMMENT,
                        "COMMENT_DLQ_DUPLICATE_REMOVED",
                        mapOf("commentId" to payload.id, "dlqId" to dl.id.toString())
                    )
                    continue
                }

                // 7. Reconstruct pending interaction with reset retry state
                val reconstructedPending = PendingInteractionEntity(
                    userId = dl.userId,
                    artifactId = dl.artifactId,
                    interactionType = InteractionType.COMMENT,
                    action = dl.action.ifBlank { InteractionAction.ADD },
                    metadata = dl.metadata,
                    createdAt = dl.createdAt,
                    correlationId = dl.correlationId.ifBlank { UUID.randomUUID().toString() },
                    workerId = null,
                    retryCount = 0,
                    lastError = null
                )

                // 8. Authoritative atomic recovery transaction inside Room:
                // Performs duplicate detection and DLQ deletion within the Room transaction
                val recoveryResult = deadLetterInteractionDao.get().performAtomicRecovery(dl.id, reconstructedPending)

                when (recoveryResult) {
                    DlqRecoveryResult.SUCCESS -> {
                        recoveredCount++
                        diagnosticLogger.info(
                            DiagnosticCategory.COMMENT,
                            "COMMENT_DLQ_RECOVERED_TO_PENDING",
                            mapOf("commentId" to payload.id, "dlqId" to dl.id.toString(), "artifactId" to dl.artifactId)
                        )
                    }
                    DlqRecoveryResult.DUPLICATE_REMOVED -> {
                        diagnosticLogger.info(
                            DiagnosticCategory.COMMENT,
                            "COMMENT_DLQ_DUPLICATE_REMOVED",
                            mapOf("commentId" to payload.id, "dlqId" to dl.id.toString())
                        )
                    }
                    DlqRecoveryResult.NOT_FOUND_OR_ALREADY_PROCESSED -> {
                        diagnosticLogger.info(
                            DiagnosticCategory.COMMENT,
                            "COMMENT_DLQ_ALREADY_PROCESSED",
                            mapOf("commentId" to payload.id, "dlqId" to dl.id.toString())
                        )
                    }
                }
            }

            Result.success(recoveredCount)
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.COMMENT,
                "COMMENT_DLQ_RECOVERY_FAILED",
                mapOf("userId" to userId),
                e
            )
            Result.failure(AppError.from(e))
        }
    }

    override suspend fun syncPendingCommentIntents(userId: String) = withContext(Dispatchers.IO) {
        if (userId.isEmpty() || authRepository.currentUserId != userId) return@withContext

        try {
            recoverEligibleDeadLetters(userId)
        } catch (e: Exception) {
            diagnosticLogger.warn(
                DiagnosticCategory.COMMENT,
                "COMMENT_DLQ_RECOVERY_BEFORE_SYNC_FAILED",
                mapOf("userId" to userId, "error" to (e.message ?: ""))
            )
        }

        val pending = pendingInteractionDao.get().getPendingForUser(userId)
        val commentInteractions = pending.filter { it.interactionType == InteractionType.COMMENT }

        for (interaction in commentInteractions) {
            val commentJson = interaction.metadata ?: continue
            val payload = try {
                json.decodeFromString(CommentPayload.serializer(), commentJson)
            } catch (_: Exception) {
                continue
            }

            val commentId = payload.id
            if (commentId.isEmpty()) continue

            val docRef = firestore.collection("users").document(userId)
                .collection("private").document("intents")
                .collection("comments").document(commentId)

            val snapshot = try {
                docRef.get().await()
            } catch (e: Exception) {
                null
            }

            if (snapshot == null || !snapshot.exists()) {
                val comment = payload.toDomain()
                val createResult = createComment(comment)
                if (createResult.isSuccess) {
                    val updatedPayload = payload.copy(intentSubmitted = true, intentStatus = "PENDING")
                    val updatedJson = json.encodeToString(CommentPayload.serializer(), updatedPayload)
                    pendingInteractionDao.get().insert(interaction.copy(metadata = updatedJson))
                    observeIntentStatus(userId, commentId, interaction.artifactId)
                }
            } else {
                val status = snapshot.getString("status") ?: "PENDING"
                handleIntentSnapshotUpdate(userId, commentId, interaction.artifactId, status, snapshot)
                if (status == "PENDING" || status == "RATE_LIMITED") {
                    observeIntentStatus(userId, commentId, interaction.artifactId)
                    if (status == "RATE_LIMITED") {
                        val rateLimitResetAt = snapshot.getLong("rateLimitResetAt")
                        if (rateLimitResetAt != null && System.currentTimeMillis() >= rateLimitResetAt) {
                            requestCommentRetry(userId, commentId)
                        }
                    }
                }
            }
        }
    }

    override fun stopIntentObservation(commentId: String) {
        activeIntentListeners.remove(commentId)?.remove()
    }

    override fun stopAllIntentObservations() {
        val iterator = activeIntentListeners.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.remove()
            iterator.remove()
        }
    }

    companion object {
        fun calculateClientRetryTimestamp(
            rateLimitResetAt: Long?,
            oldClientRetry: Long?,
            lastConsumed: Long?,
            localNowMs: Long = System.currentTimeMillis()
        ): Long {
            val prevMax = maxOf(oldClientRetry ?: 0L, lastConsumed ?: 0L)
            val candidate = maxOf(localNowMs, prevMax + 1L)
            return if (rateLimitResetAt != null && rateLimitResetAt > prevMax && candidate > rateLimitResetAt) {
                rateLimitResetAt
            } else {
                candidate
            }
        }
    }
}
