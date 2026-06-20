package com.saurabh.artifact.domain.review.comments

import com.saurabh.artifact.domain.review.ReviewPolicy
import javax.inject.Inject

/**
 * Policy for what constitutes a "meaningful listen" before an artifact's thoughts can be unlocked.
 * Initial values match publishing to maintain behavior.
 */
class CommentUnlockPolicy @Inject constructor(
    val minCoverage: Float = 0.95f,
    val requireReachedEnd: Boolean = true
) {
    fun getSegmentSizeMs(durationMs: Long): Long {
        return ReviewPolicy().getSegmentSizeMs(durationMs)
    }
}
