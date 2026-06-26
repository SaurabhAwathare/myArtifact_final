package com.saurabh.artifact.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingInteractionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(interaction: PendingInteractionEntity)

    @Delete
    suspend fun delete(interaction: PendingInteractionEntity)

    @Query("SELECT * FROM pending_interactions WHERE userId = :userId ORDER BY createdAt ASC")
    suspend fun getPendingForUser(userId: String): List<PendingInteractionEntity>

    @Query("SELECT * FROM pending_interactions WHERE artifactId = :artifactId AND userId = :userId")
    fun observePendingForArtifact(artifactId: String, userId: String): Flow<List<PendingInteractionEntity>>

    @Query("DELETE FROM pending_interactions WHERE artifactId = :artifactId AND userId = :userId AND interactionType = :type")
    suspend fun deleteByType(artifactId: String, userId: String, type: String)

    @Query("SELECT COUNT(*) FROM pending_interactions WHERE userId = :userId")
    fun getCount(userId: String): Flow<Int>

    @Query("DELETE FROM pending_interactions WHERE createdAt < :timestamp")
    suspend fun deleteOldInteractions(timestamp: Long)
}
