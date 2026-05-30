package com.saurabh.artifact.audio.validation

/**
 * Unified Rule Engine for validating if an artifact has been sufficiently reviewed.
 */
interface ReviewValidator {

    /**
     * Validates the collected evidence against production rules.
     */
    fun validate(
        coveragePercent: Float,
        effortPercent: Float,
        reachedEnd: Boolean,
        durationMs: Long = 0L
    ): ReviewResult
}
