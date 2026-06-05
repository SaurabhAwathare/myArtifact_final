package com.saurabh.artifact.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingInteractionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(interaction: PendingInteractionEntity)

    @Delete
    suspend fun delete(interaction: PendingInteractionEntity)

    @Query("SELECT * FROM pending_interactions ORDER BY createdAt ASC")
    suspend fun getAllPending(): List<PendingInteractionEntity>

    @Query("SELECT * FROM pending_interactions WHERE artifactId = :artifactId")
    fun observePendingForArtifact(artifactId: String): Flow<List<PendingInteractionEntity>>

    @Query("DELETE FROM pending_interactions WHERE artifactId = :artifactId AND interactionType = :type")
    suspend fun deleteByType(artifactId: String, type: String)

    @Query("SELECT COUNT(*) FROM pending_interactions")
    fun getCount(): Flow<Int>
}
