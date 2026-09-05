package com.saurabh.artifact.repository

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.saurabh.artifact.data.remote.model.CommentDto
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.Comment
import com.saurabh.artifact.model.CommentStatus
import com.saurabh.artifact.model.toDomain
import com.saurabh.artifact.model.toDto
import com.saurabh.artifact.util.CommentConstants
import com.saurabh.artifact.data.local.InteractionAction
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.PendingInteractionEntity
import com.saurabh.artifact.data.remote.model.CommentPayload
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.worker.InteractionSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreCommentRepository @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val firestore: FirebaseFirestore,
    private val ignoredUserDao: dagger.Lazy<com.saurabh.artifact.data.local.IgnoredUserDao>,
    private val pendingInteractionDao: dagger.Lazy<com.saurabh.artifact.data.local.PendingInteractionDao>,
    private val authRepository: AuthRepository,
    private val diagnosticLogger: DiagnosticLogger
) : CommentRepository {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

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
                val authorAnonId = commentDto?.author?.anonymousId ?: doc.getString("authorAnonymousId")
                
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
            
            // R070 FIX: Write to the private intent collection instead of public artifacts.
            // This enables server-side rate limiting and authoritative unlock validation.
            val intentRef = firestore.collection("users").document(currentUserId)
                .collection("private").document("intents")
                .collection("comments").document(if (comment.id.isNotEmpty()) comment.id else UUID.randomUUID().toString())

            val dto = comment.toDto().apply {
                authorAnonymousId = comment.author.anonymousId
            }

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
                mapOf(com.saurabh.artifact.diagnostics.LogKeys.ARTIFACT_ID to comment.artifactId),
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
            val pending = com.saurabh.artifact.data.local.PendingInteractionEntity(
                userId = currentUserId,
                artifactId = comment.artifactId,
                interactionType = com.saurabh.artifact.data.local.InteractionType.COMMENT,
                action = com.saurabh.artifact.data.local.InteractionAction.ADD,
                metadata = commentJson
            )
            
            pendingInteractionDao.get().insert(pending)
            com.saurabh.artifact.worker.InteractionSyncWorker.enqueue(context)
            
            diagnosticLogger.info(
                DiagnosticCategory.COMMENT, 
                "COMMENT_QUEUED_LOCALLY", 
                mapOf(com.saurabh.artifact.diagnostics.LogKeys.ARTIFACT_ID to comment.artifactId, "commentId" to comment.id)
            )
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.COMMENT,
                "COMMENT_ENQUEUE_FAILED",
                mapOf(com.saurabh.artifact.diagnostics.LogKeys.ARTIFACT_ID to comment.artifactId),
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
}
