package com.saurabh.artifact.util

/**
 * Constants related to the Artifact Comment System.
 */
object CommentConstants {
    // Validation Constants
    const val MIN_COMMENT_LENGTH = 1
    const val MAX_COMMENT_LENGTH = 1000

    // DLQ Recovery Constants
    const val DLQ_RECOVERY_COOLDOWN_MS = 15 * 60 * 1000L // 15 minutes

    // Firestore Collection Constants
    // Path structure: artifacts/{artifactId}/comments/{commentId}
    const val COLLECTION_ARTIFACTS = "artifacts"
    const val SUB_COLLECTION_COMMENTS = "comments"

    /**
     * Helper to get the path to the comments sub-collection for a specific artifact.
     */
    fun getCommentsCollectionPath(artifactId: String): String {
        return "$COLLECTION_ARTIFACTS/$artifactId/$SUB_COLLECTION_COMMENTS"
    }
}
