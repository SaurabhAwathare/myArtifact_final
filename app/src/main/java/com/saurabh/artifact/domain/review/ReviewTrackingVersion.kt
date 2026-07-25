package com.saurabh.artifact.domain.review

/**
 * Enumeration of supported review segmentation algorithms.
 * Used to ensure backward compatibility as the validation logic evolves.
 */
enum class ReviewTrackingVersion(val value: Int) {
    /**
     * Legacy bucketed segmentation: 500ms (<1m), 5s (<10m), 10s (>10m).
     */
    LEGACY_BUCKETED(1),

    /**
     * Modern fixed 1000ms segmentation for all durations.
     */
    FIXED_ONE_SECOND(2);

    companion object {
        fun fromInt(value: Int) = entries.find { it.value == value } ?: LEGACY_BUCKETED
        
        val CURRENT = FIXED_ONE_SECOND
    }
}
