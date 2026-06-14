package com.saurabh.artifact.domain.review

/**
 * Configuration for what constitutes a "meaningful review" for an artifact.
 */
data class ReviewPolicy(
    val minCoverage: Float = 0.95f,
    val requireReachedEnd: Boolean = true,
    private val baseSegmentSizeMs: Long = 5000L
) {
    /**
     * Dynamically scales the segment size based on total duration.
     * Prevents over-segmentation for long-form audio.
     */
    fun getSegmentSizeMs(durationMs: Long): Long {
        return when {
            durationMs < 60_000 -> 500L // 500ms for < 1 min
            durationMs < 600_000 -> 5000L // 5s for < 10 mins
            else -> 10_000L // 10s for > 10 mins
        }
    }
}
