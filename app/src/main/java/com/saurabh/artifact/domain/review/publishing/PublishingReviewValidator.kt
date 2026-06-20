package com.saurabh.artifact.domain.review.publishing

import com.saurabh.artifact.audio.validation.ReviewResult
import com.saurabh.artifact.domain.review.EngagementEvidence
import javax.inject.Inject

/**
 * Validator for Publishing Review logic.
 */
class PublishingReviewValidator @Inject constructor() {

    fun validate(
        evidence: EngagementEvidence,
        policy: PublishingReviewPolicy
    ): ReviewResult {
        
        val segmentSize = policy.getSegmentSizeMs(evidence.durationMs)
        val totalSegments = if (evidence.durationMs > 0) {
            (evidence.durationMs / segmentSize).toInt().coerceAtLeast(1)
        } else 1
        
        val coveragePercent = evidence.coverage.cardinality().toFloat() / totalSegments
        
        val isValid = coveragePercent >= policy.minCoverage &&
                     (!policy.requireReachedEnd || evidence.hasReachedEnd)

        return ReviewResult(
            coveragePercent = coveragePercent,
            reachedEnd = evidence.hasReachedEnd,
            isValid = isValid
        )
    }
}
