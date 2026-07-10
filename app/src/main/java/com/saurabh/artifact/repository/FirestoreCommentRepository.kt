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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreCommentRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val diagnosticLogger: DiagnosticLogger
) : CommentRepository {

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
            val comments = snapshot.documents.mapNotNull { doc ->
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
            val dto = comment.toDto()
            val collectionRef = firestore.collection(CommentConstants.getCommentsCollectionPath(comment.artifactId))
            
            // Use provided ID if available, otherwise generate new one
            val docRef = if (comment.id.isNotEmpty()) {
                collectionRef.document(comment.id)
            } else {
                collectionRef.document()
            }

            docRef.set(dto).await()
            
            Result.success(comment.copy(id = docRef.id))
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.FIRESTORE,
                "COMMENT_CREATE_FAILED",
                mapOf("artifactId" to comment.artifactId),
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
