package com.saurabh.artifact.audio.validation

import com.saurabh.artifact.domain.review.EngagementEvidence

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
    val evidence: EngagementEvidence,
    val reviewResult: ReviewResult? = null
)
