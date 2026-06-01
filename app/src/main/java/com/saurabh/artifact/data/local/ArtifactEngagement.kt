package com.saurabh.artifact.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists both playback position (for resume) and listening evidence (for validation).
 * Unified source of truth for user interaction with an artifact.
 */
@Entity(tableName = "artifact_engagement")
data class ArtifactEngagement(
    @PrimaryKey val artifactId: String,
    val versionTag: String,
    val durationMs: Long,
    val audioChecksum: String = "",
    val coverage: ByteArray, // Serialized BitSet
    val effortMap: Map<Float, Long>, // Speed -> Time spent (ms)
    val lastPositionMs: Long, // Resume position
    val furthestPositionMs: Long, // Validation progress
    val hasReachedEnd: Boolean,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ArtifactEngagement

        if (artifactId != other.artifactId) return false
        if (versionTag != other.versionTag) return false
        if (durationMs != other.durationMs) return false
        if (audioChecksum != other.audioChecksum) return false
        if (!coverage.contentEquals(other.coverage)) return false
        if (effortMap != other.effortMap) return false
        if (lastPositionMs != other.lastPositionMs) return false
        if (furthestPositionMs != other.furthestPositionMs) return false
        if (hasReachedEnd != other.hasReachedEnd) return false
        if (lastUpdated != other.lastUpdated) return false

        return true
    }

    override fun hashCode(): Int {
        var result = artifactId.hashCode()
        result = 31 * result + versionTag.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + audioChecksum.hashCode()
        result = 31 * result + coverage.contentHashCode()
        result = 31 * result + effortMap.hashCode()
        result = 31 * result + lastPositionMs.hashCode()
        result = 31 * result + furthestPositionMs.hashCode()
        result = 31 * result + hasReachedEnd.hashCode()
        result = 31 * result + lastUpdated.hashCode()
        return result
    }
}
