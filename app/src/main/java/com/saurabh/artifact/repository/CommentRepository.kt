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
     * Enqueues a comment submission for durable background synchronization.
     *
     * @param comment The comment to enqueue.
     * @return A [Result] indicating if the enqueue operation was successful.
     */
    suspend fun enqueueComment(comment: Comment): Result<Unit>

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

    /**
     * Observes single document snapshot for a private comment intent.
     */
    suspend fun observeIntentStatus(userId: String, commentId: String, artifactId: String)

    /**
     * Sends an authorized client retry request for a RATE_LIMITED comment intent.
     */
    suspend fun requestCommentRetry(userId: String, commentId: String): Result<Unit>

    /**
     * Scans and synchronizes unconfirmed or rate-limited pending comment intents.
     */
    suspend fun syncPendingCommentIntents(userId: String)

    /**
     * Recovers eligible comment DLQ records back into pending_interactions.
     */
    suspend fun recoverEligibleDeadLetters(userId: String): Result<Int>

    /**
     * Stops single document snapshot observation for a specific comment intent.
     */
    fun stopIntentObservation(commentId: String)

    /**
     * Stops all active comment intent snapshot listeners.
     */
    fun stopAllIntentObservations()
}
