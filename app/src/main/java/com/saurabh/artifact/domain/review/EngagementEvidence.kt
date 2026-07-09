package com.saurabh.artifact.domain.review

import java.util.BitSet

/**
 * Domain model representing the listening evidence and playback state for an artifact.
 * Unifies resume-play data with anti-scrubbing validation evidence.
 */
data class EngagementEvidence(
    val artifactId: String,
    val versionTag: String,
    val durationMs: Long,
    val audioChecksum: String = "", // Ensure evidence matches specific audio content
    val coverage: BitSet = BitSet(),
    val lastPositionMs: Long = 0L, // Current playback position for resuming
    val furthestPositionMs: Long = 0L, // Max position reached through valid playback
    val hasReachedEnd: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis(),
)
