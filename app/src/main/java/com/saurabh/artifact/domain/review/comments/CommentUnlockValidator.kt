package com.saurabh.artifact.domain.review.comments

import com.saurabh.artifact.audio.validation.ReviewResult
import com.saurabh.artifact.domain.review.EngagementEvidence
import javax.inject.Inject

/**
 * Validator for Comment Unlock logic.
 */
class CommentUnlockValidator @Inject constructor() {

    fun validate(
        evidence: EngagementEvidence,
        policy: CommentUnlockPolicy
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

    /**
     * Determines the [LocalEligibility] based on Room evidence and server unlock state.
     */
    fun getEligibility(
        evidence: EngagementEvidence?,
        policy: CommentUnlockPolicy,
        isServerUnlocked: Boolean
    ): LocalEligibility {
        if (isServerUnlocked) return LocalEligibility.ELIGIBLE_SERVER_CONFIRMED
        
        if (evidence == null) return LocalEligibility.NOT_ELIGIBLE

        val result = validate(evidence, policy)
        return if (result.isValid) {
            LocalEligibility.ELIGIBLE_LOCAL
        } else {
            LocalEligibility.NOT_ELIGIBLE
        }
    }
}
