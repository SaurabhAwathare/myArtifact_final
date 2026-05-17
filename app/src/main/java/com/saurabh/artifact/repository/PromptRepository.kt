package com.saurabh.artifact.repository

import android.content.Context
import com.saurabh.artifact.data.local.PromptDao
import com.saurabh.artifact.data.local.toDomainModel
import com.saurabh.artifact.data.local.toEntity
import com.saurabh.artifact.model.PromptCategory
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.model.ReflectionQuestion
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
    @ApplicationContext private val context: Context
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

    fun getPromptsByCategory(category: PromptCategory): Flow<List<ReflectionPrompt>> =
        promptDao.getPromptsByCategory(category).map { list -> list.map { it.toDomainModel() } }

    fun getFavoritePrompts(): Flow<List<ReflectionPrompt>> =
        promptDao.getFavoritePrompts().map { list -> list.map { it.toDomainModel() } }

    suspend fun toggleFavorite(id: String, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        promptDao.updateFavoriteStatus(id, isFavorite)
    }

    suspend fun recordUsage(id: String) = withContext(Dispatchers.IO) {
        promptDao.recordUsage(id)
    }

    // LEVEL 1: GROUNDING (Start speaking easily - Remove friction)
    private val level1 = listOf(
        ReflectionQuestion("l1_1", "What's one thing that's been on your mind lately?", 1, tags = listOf("focus", "current-state")),
        ReflectionQuestion("l1_2", "How are you feeling in this exact moment?", 1, tags = listOf("emotion", "presence")),
        ReflectionQuestion("l1_3", "If you could describe your day in one word, what would it be?", 1, tags = listOf("daily-vibe")),
        ReflectionQuestion("l1_4", "What was the first thing you thought about this morning?", 1, tags = listOf("subconscious", "morning")),
        ReflectionQuestion("l1_5", "What's something 'real' you noticed today?", 1, tags = listOf("observation", "grounding"))
    )

    // LEVEL 2: EXPLORATION (Stay with experience - Deepen awareness gently)
    private val level2 = listOf(
        ReflectionQuestion("l2_1", "What part of that feels the most important right now?", 2, tags = listOf("priority", "depth")),
        ReflectionQuestion("l2_2", "If that feeling had a color or a shape, what would it look like?", 2, tags = listOf("abstraction", "visualization")),
        ReflectionQuestion("l2_3", "Where in your body do you feel that most?", 2, tags = listOf("somatic", "body-awareness")),
        ReflectionQuestion("l2_4", "When did that feeling first show up today?", 2, tags = listOf("tracing", "triggers")),
        ReflectionQuestion("l2_5", "What's one detail about this situation that sticks out?", 2, tags = listOf("detail", "awareness"))
    )

    // LEVEL 3: POSITIVE SHIFT (Emotional balance - Introduce light without forcing)
    private val level3 = listOf(
        ReflectionQuestion("l3_1", "What's one tiny thing about this you can actually control?", 3, tags = listOf("agency", "control")),
        ReflectionQuestion("l3_2", "What's a small kindness you could show yourself right now?", 3, tags = listOf("self-compassion")),
        ReflectionQuestion("l3_3", "If a friend told you this, what would you say to them?", 3, tags = listOf("perspective", "friendship")),
        ReflectionQuestion("l3_4", "What's something you've handled before that reminds you of this?", 3, tags = listOf("resilience", "past-success")),
        ReflectionQuestion("l3_5", "Is there a small 'win' hidden in this day somewhere?", 3, tags = listOf("gratitude", "positivity"))
    )

    // LEVEL 4: GROWTH (Reflection to Learning - Insight, meaning)
    private val level4 = listOf(
        ReflectionQuestion("l4_1", "What's one thing you're letting go of as you speak?", 4, tags = listOf("release", "growth")),
        ReflectionQuestion("l4_2", "What does this situation tell you about what you value?", 4, tags = listOf("values", "identity")),
        ReflectionQuestion("l4_3", "If you were looking back at this a year from now, what would you say?", 4, tags = listOf("long-term", "wisdom")),
        ReflectionQuestion("l4_4", "What's one small step that feels possible tomorrow?", 4, tags = listOf("action", "forward-motion")),
        ReflectionQuestion("l4_5", "How has your perspective shifted since we started talking?", 4, tags = listOf("integration", "reflection"))
    )

    fun getAllQuestions(): List<ReflectionQuestion> = level1 + level2 + level3 + level4
}
