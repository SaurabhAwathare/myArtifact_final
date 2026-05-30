package com.saurabh.artifact.audio.validation

/**
 * Production implementation of the Review Validation rules.
 * 
 * Rules:
 * 1. Coverage >= 95% (The user heard almost everything)
 * 2. Effort >= 75% (The user spent real time, preventing high-speed scrubbing)
 * 3. Reached End = true (The user hit the physical end of the file)
 * 
 * Exception: For artifacts shorter than 10 seconds, effort is relaxed but coverage 
 * must be > 99%.
 */
class DefaultReviewValidator : ReviewValidator {

    override fun validate(
        coveragePercent: Float,
        effortPercent: Float,
        reachedEnd: Boolean,
        durationMs: Long
    ): ReviewResult {
        
        val thresholdCoverage = if (durationMs < 10000) 0.99f else 0.95f
        val thresholdEffort = if (durationMs < 10000) 0.0f else 0.75f

        val isValid = coveragePercent >= thresholdCoverage &&
                     effortPercent >= thresholdEffort &&
                     reachedEnd

        return ReviewResult(
            coveragePercent = coveragePercent,
            effortPercent = effortPercent,
            reachedEnd = reachedEnd,
            isValid = isValid
        )
    }
}
