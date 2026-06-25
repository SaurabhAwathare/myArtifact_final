package com.saurabh.artifact.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName

/**
 * Authoritative Firestore schema for user-artifact engagement.
 * Consolidates playback progress, validation state, and sync metadata.
 */
data class UserArtifactEngagement(
    val userId: String = "",
    val artifactId: String = "",
    @get:PropertyName("isCommentUnlocked")
    val isCommentUnlocked: Boolean = false,
    val lastPositionMs: Long = 0,    // Actual resume point
    val lastFurthestPosition: Long = 0, // Furthest point reached (for validation)
    val totalDurationMs: Long = 0,   // Cached duration for progress calculation
    val hasReachedEnd: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val coverage: ByteArray = byteArrayOf(), // Added for server-side validation
    val engagementState: EngagementState? = null // Server-managed
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UserArtifactEngagement

        if (userId != other.userId) return false
        if (artifactId != other.artifactId) return false
        if (isCommentUnlocked != other.isCommentUnlocked) return false
        if (lastPositionMs != other.lastPositionMs) return false
        if (lastFurthestPosition != other.lastFurthestPosition) return false
        if (totalDurationMs != other.totalDurationMs) return false
        if (hasReachedEnd != other.hasReachedEnd) return false
        if (updatedAt != other.updatedAt) return false
        if (!coverage.contentEquals(other.coverage)) return false
        if (engagementState != other.engagementState) return false

        return true
    }

    override fun hashCode(): Int {
        var result = userId.hashCode()
        result = 31 * result + artifactId.hashCode()
        result = 31 * result + isCommentUnlocked.hashCode()
        result = 31 * result + lastPositionMs.hashCode()
        result = 31 * result + lastFurthestPosition.hashCode()
        result = 31 * result + totalDurationMs.hashCode()
        result = 31 * result + hasReachedEnd.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + coverage.contentHashCode()
        result = 31 * result + (engagementState?.hashCode() ?: 0)
        return result
    }
}

/**
 * Server-managed engagement state.
 */
data class EngagementState(
    val unlocked: Boolean = false,
    val unlockReason: String = "",
    val unlockVersion: Int = 1,
    val evaluatedAt: Timestamp? = null
)
