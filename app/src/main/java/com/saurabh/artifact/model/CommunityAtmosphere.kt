package com.saurabh.artifact.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.IgnoreExtraProperties

/**
 * Represents a periodic snapshot of the emotional atmosphere in the Human Library.
 */
@IgnoreExtraProperties
data class CommunityAtmosphere(
    val generatedAt: Timestamp = Timestamp.now(),
    val windowStart: Timestamp = Timestamp.now(),
    val windowEnd: Timestamp = Timestamp.now(),
    val totalArtifacts: Long = 0,
    val categoryCounts: Map<String, Long> = emptyMap(),
    val status: String = "ACTIVE" // e.g., "ACTIVE", "INSUFFICIENT_DATA"
)
