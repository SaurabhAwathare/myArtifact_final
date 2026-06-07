package com.saurabh.artifact.model

/**
 * Optional Firestore schema for future cross-device syncing.
 */
data class UserArtifactEngagement(
    val userId: String,
    val artifactId: String,
    val isCommentUnlocked: Boolean,
    val lastFurthestPosition: Long,
    val updatedAt: Long = System.currentTimeMillis(),
)
