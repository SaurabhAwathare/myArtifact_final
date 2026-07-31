package com.saurabh.artifact.data.local

import androidx.room.*
import com.saurabh.artifact.model.SyncState
import kotlinx.coroutines.flow.Flow

@Dao
interface EngagementDao {
    @Query("SELECT * FROM artifact_engagement WHERE artifactId = :artifactId")
    suspend fun getEngagement(artifactId: String): ArtifactEngagement?

    @Query("SELECT * FROM artifact_engagement WHERE artifactId = :artifactId")
    fun observeEngagement(artifactId: String): Flow<ArtifactEngagement?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEngagement(engagement: ArtifactEngagement)

    @Query("SELECT * FROM artifact_engagement WHERE syncState = 'PENDING' OR syncState = 'FAILED'")
    suspend fun getEngagementsRequiringSync(): List<ArtifactEngagement>

    @Query("UPDATE artifact_engagement SET syncState = :state, lastSyncAttempt = :timestamp, lastSyncError = :error, syncRetryCount = syncRetryCount + 1 WHERE artifactId = :artifactId")
    suspend fun updateSyncStatus(artifactId: String, state: SyncState, timestamp: Long, error: String?)

    @Query("UPDATE artifact_engagement SET syncState = 'SYNCED', lastSyncSuccess = :timestamp, syncRetryCount = 0, lastSyncError = null WHERE artifactId = :artifactId AND syncState = 'SYNCING'")
    suspend fun markAsSynced(artifactId: String, timestamp: Long): Int

    @Query("""
    UPDATE artifact_engagement 
    SET syncState = 'PENDING' 
    WHERE syncState = 'SYNCING'
    """)
    suspend fun reclaimOrphanedSyncs(): Int

    @Query("DELETE FROM artifact_engagement WHERE artifactId = :artifactId")
    suspend fun deleteEngagement(artifactId: String)
    
    @Query("UPDATE artifact_engagement SET lastPositionMs = :positionMs, lastUpdated = :timestamp, syncState = 'PENDING' WHERE artifactId = :artifactId")
    suspend fun updateLastPosition(artifactId: String, positionMs: Long, timestamp: Long = System.currentTimeMillis()): Int

    @Query("UPDATE artifact_engagement SET isCommentUnlocked = :isUnlocked, unlockTimestamp = :timestamp, engagementState = :state, unlockReason = :reason, remoteUpdatedAt = :remoteUpdated WHERE artifactId = :artifactId AND (remoteUpdatedAt IS NULL OR remoteUpdatedAt < :remoteUpdated)")
    suspend fun updateUnlockStatus(artifactId: String, isUnlocked: Boolean, timestamp: Long?, state: String, reason: String?, remoteUpdated: Long?)

    @Query("DELETE FROM artifact_engagement WHERE lastUpdated < :timestamp")
    suspend fun deleteOldEngagements(timestamp: Long)
}
