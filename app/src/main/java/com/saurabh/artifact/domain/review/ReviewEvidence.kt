package com.saurabh.artifact.domain.review

import java.util.BitSet

/**
 * Domain model representing the listening evidence collected for an artifact.
 */
data class ReviewEvidence(
    val artifactId: String,
    val versionTag: String,
    val durationMs: Long,
    val audioChecksum: String = "", // Ensure evidence matches specific audio content
    val coverage: BitSet = BitSet(),
    val effortMap: Map<Float, Long> = emptyMap(), // Speed -> Time spent in ms
    val furthestPositionMs: Long = 0L,
    val hasReachedEnd: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    /**
     * Calculates the total "Raw Effort" (wall clock time).
     */
    fun getTotalEffortMs(): Long = effortMap.values.sum()

    /**
     * Calculates the "Adjusted Effort" based on playback speed.
     * Speed > penaltyThreshold reduces the effort contribution.
     */
    fun getAdjustedEffortMs(penaltyThreshold: Float = 2.0f): Long {
        return effortMap.entries.sumOf { (speed, duration) ->
            if (speed > penaltyThreshold) {
                (duration * (penaltyThreshold / speed)).toLong()
            } else {
                duration
            }
        }
    }
}
