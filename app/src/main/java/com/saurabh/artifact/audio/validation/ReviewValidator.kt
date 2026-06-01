package com.saurabh.artifact.audio.validation

import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.domain.review.ReviewPolicy

/**
 * Unified Rule Engine for validating if an artifact has been sufficiently reviewed.
 */
interface ReviewValidator {

    /**
     * Validates the collected evidence against a specific policy.
     */
    fun validate(
        evidence: EngagementEvidence,
        policy: ReviewPolicy
    ): ReviewResult
}
