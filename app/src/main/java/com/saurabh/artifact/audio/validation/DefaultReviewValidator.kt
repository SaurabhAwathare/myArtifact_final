package com.saurabh.artifact.audio.validation

import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.domain.review.ReviewPolicy

/**
 * Production implementation of the Review Validation rules.
 */
class DefaultReviewValidator : ReviewValidator {

    override fun validate(
        evidence: EngagementEvidence,
        policy: ReviewPolicy
    ): ReviewResult {
        
        val segmentSize = policy.getSegmentSizeMs(evidence.durationMs)
        val totalSegments = if (evidence.durationMs > 0) {
            (evidence.durationMs / segmentSize).toInt().coerceAtLeast(1)
        } else 1
        
        val coveragePercent = evidence.coverage.cardinality().toFloat() / totalSegments
        
        val adjustedEffortMs = evidence.getAdjustedEffortMs(policy.maxSpeedPenaltyThreshold)
        val effortPercent = adjustedEffortMs.toFloat() / evidence.durationMs.coerceAtLeast(1)

        val isValid = coveragePercent >= policy.minCoverage &&
                     effortPercent >= policy.minEffort &&
                     (!policy.requireReachedEnd || evidence.hasReachedEnd)

        return ReviewResult(
            coveragePercent = coveragePercent,
            effortPercent = effortPercent,
            reachedEnd = evidence.hasReachedEnd,
            isValid = isValid
        )
    }
}
