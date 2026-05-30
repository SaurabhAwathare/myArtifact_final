package com.saurabh.artifact.audio.validation

/**
 * Immutable snapshot of the review state for a specific artifact.
 */
data class ReviewProgress(
    val artifactId: String,
    val durationMs: Long,
    val coveragePercent: Float,
    val effortPercent: Float,
    val hasReachedEnd: Boolean,
    val isValidationMet: Boolean,
    val rawP1: Long,
    val rawP2: Long,
    val totalTimeListenedMs: Long,
    val furthestPositionMs: Long,
    val reviewResult: ReviewResult? = null
)
