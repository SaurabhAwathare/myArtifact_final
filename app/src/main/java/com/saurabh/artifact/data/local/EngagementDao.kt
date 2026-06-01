package com.saurabh.artifact.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface EngagementDao {
    @Query("SELECT * FROM artifact_engagement WHERE artifactId = :artifactId")
    suspend fun getEngagement(artifactId: String): ArtifactEngagement?

    @Query("SELECT * FROM artifact_engagement WHERE artifactId = :artifactId")
    fun observeEngagement(artifactId: String): Flow<ArtifactEngagement?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEngagement(engagement: ArtifactEngagement)

    @Query("DELETE FROM artifact_engagement WHERE artifactId = :artifactId")
    suspend fun deleteEngagement(artifactId: String)
    
    @Query("UPDATE artifact_engagement SET lastPositionMs = :positionMs, lastUpdated = :timestamp WHERE artifactId = :artifactId")
    suspend fun updateLastPosition(artifactId: String, positionMs: Long, timestamp: Long = System.currentTimeMillis())
}
