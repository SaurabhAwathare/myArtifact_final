package com.saurabh.artifact.repository

import android.content.Context
import com.saurabh.artifact.data.local.PromptDao
import com.saurabh.artifact.data.local.toDomainModel
import com.saurabh.artifact.data.local.toEntity
import com.saurabh.artifact.model.ReflectionPrompt
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PromptRepository @Inject constructor(
    private val promptDao: PromptDao,
    @param:ApplicationContext private val context: Context
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Initializes the database with prompts from JSON if it's empty.
     */
    suspend fun initializeIfEmpty() = withContext(Dispatchers.IO) {
        if (promptDao.getPromptCount() == 0) {
            try {
                val jsonString = context.assets.open("prompts.json").bufferedReader().use { it.readText() }
                val prompts = json.decodeFromString<List<ReflectionPrompt>>(jsonString)
                promptDao.insertPrompts(prompts.map { it.toEntity() })
            } catch (e: Exception) {
                android.util.Log.e("PromptRepository", "Failed to preload prompts", e)
            }
        }
    }

    fun getAllPrompts(): Flow<List<ReflectionPrompt>> = 
        promptDao.getAllPrompts().map { list -> list.map { it.toDomainModel() } }

    /**
     * Fetches a random prompt from the database, optionally filtered by mood.
     */
    suspend fun getRandomPrompt(mood: String? = null): ReflectionPrompt? = withContext(Dispatchers.IO) {
        initializeIfEmpty() // Ensure we have prompts if this is called early
        
        val entity = if (mood != null) {
            promptDao.getRandomPromptByMood(mood) ?: promptDao.getRandomPrompt()
        } else {
            promptDao.getRandomPrompt()
        }
        
        entity?.toDomainModel()
    }

    /**
     * Records that a prompt was used to help track variety in the future.
     */
    suspend fun recordUsage(promptId: String) = withContext(Dispatchers.IO) {
        promptDao.recordUsage(promptId)
    }
}
