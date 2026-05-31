package com.saurabh.artifact.audio.validation

import com.saurabh.artifact.domain.review.ReviewEvidence
import com.saurabh.artifact.domain.review.ReviewPolicy

/**
 * Unified Rule Engine for validating if an artifact has been sufficiently reviewed.
 */
interface ReviewValidator {

    /**
     * Validates the collected evidence against a specific policy.
     */
    fun validate(
        evidence: ReviewEvidence,
        policy: ReviewPolicy
    ): ReviewResult
}
