package com.saurabh.artifact.domain.review

/**
 * Data Transfer Object for serializing engagement evidence for synchronization.
 * Contains only raw evidence; no derived state.
 */
data class EngagementSyncPayload(
    val artifactId: String,
    val lastPositionMs: Long,
    val furthestPositionMs: Long,
    val durationMs: Long,
    val hasReachedEnd: Boolean,
    val coverage: String, // Base64 encoded byte array from BitSet
    val lastUpdated: Long
)
