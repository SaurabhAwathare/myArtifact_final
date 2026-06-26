package com.saurabh.artifact.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DeadLetterInteractionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(interaction: DeadLetterInteractionEntity)

    @Query("SELECT * FROM dead_letter_interactions ORDER BY failedAt DESC")
    suspend fun getAll(): List<DeadLetterInteractionEntity>

    @Query("DELETE FROM dead_letter_interactions WHERE id = :id")
    suspend fun deleteById(id: Long)
}
