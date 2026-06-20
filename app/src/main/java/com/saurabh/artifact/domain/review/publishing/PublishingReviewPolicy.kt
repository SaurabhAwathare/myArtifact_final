package com.saurabh.artifact.domain.review.publishing

import com.saurabh.artifact.domain.review.ReviewPolicy
import javax.inject.Inject

/**
 * Policy for what constitutes a "meaningful review" before an artifact can be published.
 * Currently strict: 95% coverage and reaching the end.
 */
class PublishingReviewPolicy @Inject constructor(
    val minCoverage: Float = 0.95f,
    val requireReachedEnd: Boolean = true
) {
    /**
     * Reuse the segment sizing strategy from the base policy or define its own.
     */
    fun getSegmentSizeMs(durationMs: Long): Long {
        // For now, delegating to the existing shared logic to maintain initial behavior
        return ReviewPolicy().getSegmentSizeMs(durationMs)
    }
}
