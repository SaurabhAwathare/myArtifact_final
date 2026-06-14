package com.saurabh.artifact.audio.validation

/**
 * The outcome of a review validation rule engine.
 */
data class ReviewResult(
    val coveragePercent: Float,
    val reachedEnd: Boolean,
    val isValid: Boolean
)
