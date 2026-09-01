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
import com.saurabh.artifact.worker.InteractionSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
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
                val creatorId = doc.getString("creatorId")
                if (creatorId != null && ignoredIds.contains(creatorId)) return@mapNotNull null
                
                doc.toObject(CommentDto::class.java)?.copy(id = doc.id)?.toDomain()
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
        try {
            diagnosticLogger.info(
                DiagnosticCategory.COMMENT, 
                "COMMENT_SUBMIT_STARTED", 
                mapOf(com.saurabh.artifact.diagnostics.LogKeys.ARTIFACT_ID to comment.artifactId)
            )
            val dto = comment.toDto().apply {
                authorAnonymousId = comment.author.anonymousId
            }
            val collectionRef = firestore.collection(CommentConstants.getCommentsCollectionPath(comment.artifactId))
            
            // Use provided ID if available, otherwise generate new one
            val docRef = if (comment.id.isNotEmpty()) {
                collectionRef.document(comment.id)
            } else {
                collectionRef.document()
            }

            docRef.set(dto).await()
            
            diagnosticLogger.info(
                DiagnosticCategory.COMMENT, 
                "COMMENT_SUBMIT_SUCCESS", 
                mapOf(com.saurabh.artifact.diagnostics.LogKeys.ARTIFACT_ID to comment.artifactId, "commentId" to docRef.id)
            )
            
            Result.success(comment.copy(id = docRef.id))
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.COMMENT,
                "COMMENT_SUBMIT_FAILED",
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
            val commentJson = json.encodeToString(comment)
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
