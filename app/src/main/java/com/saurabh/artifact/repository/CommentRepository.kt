package com.saurabh.artifact.repository

import com.google.firebase.firestore.DocumentSnapshot
import com.saurabh.artifact.model.Comment

/**
 * Result wrapper for paginated comments.
 *
 * @property comments The list of comments for the current page.
 * @property lastVisible The Firestore document snapshot used as a cursor for the next page.
 */
data class PaginatedComments(
    val comments: List<Comment>,
    val lastVisible: DocumentSnapshot?
)

/**
 * Repository interface for managing artifact comments in Firestore.
 */
interface CommentRepository {
    /**
     * Loads a paginated list of comments for a specific artifact.
     *
     * @param artifactId The ID of the artifact.
     * @param limit The maximum number of comments to fetch.
     * @param lastVisible The cursor for pagination.
     * @return A [Result] containing [PaginatedComments].
     */
    suspend fun getComments(
        artifactId: String,
        limit: Int = 20,
        lastVisible: DocumentSnapshot? = null
    ): Result<PaginatedComments>

    /**
     * Creates a new comment.
     *
     * @param comment The comment domain model to persist.
     * @return A [Result] containing the created [Comment].
     */
    suspend fun createComment(comment: Comment): Result<Comment>

    /**
     * Deletes a comment (soft delete).
     *
     * @param artifactId The ID of the artifact.
     * @param commentId The ID of the comment to delete.
     * @return A [Result] indicating success or failure.
     */
    suspend fun deleteComment(
        artifactId: String,
        commentId: String
    ): Result<Unit>
}
