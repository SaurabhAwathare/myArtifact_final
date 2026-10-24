package com.saurabh.artifact.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DeadLetterInteractionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(interaction: DeadLetterInteractionEntity)

    @Delete
    abstract suspend fun deletePending(pending: PendingInteractionEntity)

    @Transaction
    open suspend fun moveToDeadLetter(
        deadLetter: DeadLetterInteractionEntity,
        pending: PendingInteractionEntity
    ) {
        insert(deadLetter)
        deletePending(pending)
    }

    @Query("SELECT * FROM dead_letter_interactions ORDER BY failedAt DESC")
    abstract suspend fun getAll(): List<DeadLetterInteractionEntity>

    @Query("DELETE FROM dead_letter_interactions WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    @Query("SELECT * FROM dead_letter_interactions WHERE artifactId = :artifactId AND userId = :userId AND interactionType = :interactionType ORDER BY failedAt DESC")
    abstract fun observeDeadLettersForArtifact(
        artifactId: String,
        userId: String,
        interactionType: String = InteractionType.COMMENT
    ): Flow<List<DeadLetterInteractionEntity>>
}
