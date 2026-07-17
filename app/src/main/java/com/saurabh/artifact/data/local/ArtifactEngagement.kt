package com.saurabh.artifact.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.saurabh.artifact.model.SyncState

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
    val lastPositionMs: Long, // Resume position
    val furthestPositionMs: Long, // Validation progress
    val hasReachedEnd: Boolean,
    val lastUpdated: Long = System.currentTimeMillis(),
    val syncState: SyncState = SyncState.PENDING,
    val lastSyncAttempt: Long = 0L,
    val lastSyncSuccess: Long = 0L,
    val syncRetryCount: Int = 0,
    val lastSyncError: String? = null,
    
    // Authoritative fields from backend
    val isCommentUnlocked: Boolean = false,
    val unlockTimestamp: Long? = null,
    val engagementState: String = "LOCKED",
    val unlockReason: String? = null,
    val remoteUpdatedAt: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArtifactEngagement) return false

        if (artifactId != other.artifactId) return false
        if (versionTag != other.versionTag) return false
        if (durationMs != other.durationMs) return false
        if (audioChecksum != other.audioChecksum) return false
        if (!coverage.contentEquals(other.coverage)) return false
        if (lastPositionMs != other.lastPositionMs) return false
        if (furthestPositionMs != other.furthestPositionMs) return false
        if (hasReachedEnd != other.hasReachedEnd) return false
        if (lastUpdated != other.lastUpdated) return false
        if (syncState != other.syncState) return false
        if (lastSyncAttempt != other.lastSyncAttempt) return false
        if (lastSyncSuccess != other.lastSyncSuccess) return false
        if (syncRetryCount != other.syncRetryCount) return false
        if (isCommentUnlocked != other.isCommentUnlocked) return false
        if (unlockTimestamp != other.unlockTimestamp) return false
        if (engagementState != other.engagementState) return false
        if (unlockReason != other.unlockReason) return false
        return lastSyncError == other.lastSyncError
    }

    override fun hashCode(): Int {
        var result = artifactId.hashCode()
        result = (31 * result) + versionTag.hashCode()
        result = (31 * result) + durationMs.hashCode()
        result = (31 * result) + audioChecksum.hashCode()
        result = (31 * result) + coverage.contentHashCode()
        result = (31 * result) + lastPositionMs.hashCode()
        result = (31 * result) + furthestPositionMs.hashCode()
        result = (31 * result) + hasReachedEnd.hashCode()
        result = (31 * result) + lastUpdated.hashCode()
        result = (31 * result) + syncState.hashCode()
        result = (31 * result) + lastSyncAttempt.hashCode()
        result = (31 * result) + lastSyncSuccess.hashCode()
        result = (31 * result) + syncRetryCount.hashCode()
        result = (31 * result) + (lastSyncError?.hashCode() ?: 0)
        result = (31 * result) + isCommentUnlocked.hashCode()
        result = (31 * result) + (unlockTimestamp?.hashCode() ?: 0)
        result = (31 * result) + engagementState.hashCode()
        result = (31 * result) + (unlockReason?.hashCode() ?: 0)
        return result
    }
}
