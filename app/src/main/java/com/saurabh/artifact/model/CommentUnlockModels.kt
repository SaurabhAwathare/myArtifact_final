package com.saurabh.artifact.model

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
)
