package com.saurabh.artifact.domain.review

/**
 * Configuration for what constitutes a "meaningful review" for an artifact.
 */
data class ReviewPolicy(
    val minCoverage: Float = 0.95f,
    val requireReachedEnd: Boolean = true
) {
    /**
     * Dynamically scales the segment size based on total duration or tracking version.
     * Prevents over-segmentation for long-form audio in legacy, or uses fixed size for modern.
     */
    fun getSegmentSizeMs(durationMs: Long, version: ReviewTrackingVersion): Long {
        return when (version) {
            ReviewTrackingVersion.LEGACY_BUCKETED -> {
                when {
                    durationMs < 60_000 -> 500L // 500ms for < 1 min
                    durationMs < 600_000 -> 5000L // 5s for < 10 mins
                    else -> 10_000L // 10s for > 10 mins
                }
            }
            ReviewTrackingVersion.FIXED_ONE_SECOND -> 1000L
        }
    }
}
