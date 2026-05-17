package com.saurabh.artifact.model

/**
 * Represents the current state of the comment section for a specific artifact.
 */
sealed class CommentUnlockState {
    /**
     * Comments are locked. Progress indicates how much has been listened to (0.0 to 1.0).
     */
    data class Locked(val progress: Float) : CommentUnlockState()

    /**
     * The moment the threshold is met, before transitioning to fully unlocked.
     * Useful for triggering "congratulatory" or "reflective" animations.
     */
    object Unlocking : CommentUnlockState()

    /**
     * Comments are fully accessible.
     */
    object Unlocked : CommentUnlockState()
}

/**
 * Internal state for tracking playback progress and anti-scrubbing.
 */
data class PlaybackSessionState(
    val artifactId: String,
    val furthestPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val isThresholdMet: Boolean = false
) {
    val progress: Float
        get() = if (totalDurationMs > 0) furthestPositionMs.toFloat() / totalDurationMs else 0f
}

/**
 * Optional Firestore schema for future cross-device syncing.
 */
data class UserArtifactEngagement(
    val userId: String,
    val artifactId: String,
    val isCommentUnlocked: Boolean,
    val lastFurthestPosition: Long,
    val updatedAt: Long = System.currentTimeMillis()
)
