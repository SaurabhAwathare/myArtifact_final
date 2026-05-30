package com.saurabh.artifact.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists raw listening evidence for any artifact (draft or public).
 */
@Entity(tableName = "artifact_review_evidence")
data class ArtifactReviewEvidence(
    @PrimaryKey val artifactId: String,
    val durationMs: Long,
    val coverageP1: Long,
    val coverageP2: Long,
    val cumulativeEffortMs: Long,
    val furthestPositionMs: Long,
    val hasReachedEnd: Boolean,
    val lastUpdated: Long = System.currentTimeMillis()
)
