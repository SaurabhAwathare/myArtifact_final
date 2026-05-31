package com.saurabh.artifact.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists raw listening evidence for any artifact (draft or public).
 * Upgraded to support granular coverage and speed-adjusted effort.
 */
@Entity(tableName = "artifact_review_evidence")
data class ArtifactReviewEvidence(
    @PrimaryKey val artifactId: String,
    val versionTag: String,
    val durationMs: Long,
    val audioChecksum: String = "", // Added for tamper resistance
    val coverage: ByteArray, // Serialized BitSet
    val effortMap: Map<Float, Long>, // Speed -> Time spent (ms)
    val furthestPositionMs: Long,
    val hasReachedEnd: Boolean,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ArtifactReviewEvidence

        if (artifactId != other.artifactId) return false
        if (versionTag != other.versionTag) return false
        if (durationMs != other.durationMs) return false
        if (!coverage.contentEquals(other.coverage)) return false
        if (effortMap != other.effortMap) return false
        if (furthestPositionMs != other.furthestPositionMs) return false
        if (hasReachedEnd != other.hasReachedEnd) return false
        if (lastUpdated != other.lastUpdated) return false

        return true
    }

    override fun hashCode(): Int {
        var result = artifactId.hashCode()
        result = 31 * result + versionTag.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + coverage.contentHashCode()
        result = 31 * result + effortMap.hashCode()
        result = 31 * result + furthestPositionMs.hashCode()
        result = 31 * result + hasReachedEnd.hashCode()
        result = 31 * result + lastUpdated.hashCode()
        return result
    }
}
