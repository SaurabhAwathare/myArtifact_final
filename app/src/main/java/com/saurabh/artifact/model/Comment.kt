package com.saurabh.artifact.model

import com.google.firebase.Timestamp

/**
 * Represents the status of a comment in the system.
 */
enum class CommentStatus {
    ACTIVE,
    DELETED,
    HIDDEN,
    MODERATED
}

/**
 * Domain model representing a comment on an artifact.
 *
 * @property id Unique identifier for the comment.
 * @property artifactId Identifier of the artifact this comment belongs to.
 * @property creatorId UID of the user who created the comment.
 * @property author A snapshot of the author's identity at the time of comment creation.
 * @property text The content of the comment.
 * @property createdAt Timestamp when the comment was created.
 * @property updatedAt Timestamp when the comment was last updated.
 * @property status Current status of the comment (e.g., ACTIVE, DELETED).
 */
data class Comment(
    val id: String = "",
    val artifactId: String = "",
    val creatorId: String = "",
    val author: AuthorSnapshot = AuthorSnapshot(),
    val text: String = "",
    val createdAt: Timestamp = Timestamp.now(),
    val updatedAt: Timestamp = Timestamp.now(),
    val status: CommentStatus = CommentStatus.ACTIVE
)
