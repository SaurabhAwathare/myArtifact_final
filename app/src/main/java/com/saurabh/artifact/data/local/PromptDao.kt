package com.saurabh.artifact.data.local

import androidx.room.*
import com.saurabh.artifact.model.PromptCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface PromptDao {
    @Query("SELECT * FROM prompts ORDER BY lastUsedTimestamp DESC")
    fun getAllPrompts(): Flow<List<PromptEntity>>

    @Query("SELECT * FROM prompts WHERE category = :category")
    fun getPromptsByCategory(category: PromptCategory): Flow<List<PromptEntity>>

    @Query("SELECT * FROM prompts WHERE isFavorite = 1")
    fun getFavoritePrompts(): Flow<List<PromptEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrompts(prompts: List<PromptEntity>)

    @Update
    suspend fun updatePrompt(prompt: PromptEntity)

    @Query("UPDATE prompts SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavoriteStatus(id: String, isFavorite: Boolean)

    @Query("UPDATE prompts SET usageCount = usageCount + 1, lastUsedTimestamp = :timestamp WHERE id = :id")
    suspend fun recordUsage(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM prompts")
    suspend fun getPromptCount(): Int
}
