package com.saurabh.artifact.repository

import android.content.Context
import android.util.Log
import com.saurabh.artifact.data.local.PromptDao
import com.saurabh.artifact.data.local.PromptEntity
import com.saurabh.artifact.data.local.toDomainModel
import com.saurabh.artifact.data.local.toEntity
import com.saurabh.artifact.model.ReflectionPrompt
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PromptRepository @Inject constructor(
    private val promptDao: Lazy<PromptDao>,
    @param:ApplicationContext private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private var isSynced = false

    /**
     * Selects the next eligible unconsumed prompt based on depth level.
     * Supports optional [excludedPromptId] to prevent immediately repeating a skipped prompt.
     * Automatically triggers Question Bank recycling when all prompts are consumed.
     */
    suspend fun getNewPrompt(excludedPromptId: String? = null): ReflectionPrompt? = withContext(Dispatchers.IO) {
        syncPromptsFromAsset()

        val consumedCount = promptDao.get().getConsumedCount()
        val maxDepth = calculateMaxDepth(consumedCount)

        var entity = promptDao.get().getNextEligiblePrompt(maxDepth, excludedPromptId) 
            ?: promptDao.get().getOldestPrompt(excludedPromptId) // Final fallback if depth exhausted

        // If exclusion produced null, check if there's any unconsumed prompt without exclusion
        if (entity == null && excludedPromptId != null) {
            entity = promptDao.get().getOldestPrompt(null)
        }

        // Exhaustion check: if no unconsumed prompts exist at all, recycle the bank
        if (entity == null && promptDao.get().getUnconsumedCount() == 0 && promptDao.get().getPromptCount() > 0) {
            promptDao.get().resetConsumedPrompts()
            val resetConsumedCount = promptDao.get().getConsumedCount()
            val resetMaxDepth = calculateMaxDepth(resetConsumedCount)
            entity = promptDao.get().getNextEligiblePrompt(resetMaxDepth, excludedPromptId)
                ?: promptDao.get().getOldestPrompt(null)
        }

        entity?.toDomainModel()
    }

    private fun calculateMaxDepth(consumedCount: Int): Int {
        return when {
            consumedCount < 30 -> 1
            consumedCount < 100 -> 2
            consumedCount < 250 -> 3
            else -> 4
        }
    }

    /**
     * Marks a prompt as permanently consumed.
     */
    suspend fun markAsConsumed(promptId: String) = withContext(Dispatchers.IO) {
        promptDao.get().markAsConsumed(promptId)
    }

    /**
     * Explicitly recycles the Question Bank by clearing consumed state across all prompts.
     */
    suspend fun recycleQuestionBank() = withContext(Dispatchers.IO) {
        promptDao.get().resetConsumedPrompts()
    }

    /**
     * Selects the least recently used prompt (context-aware if mood provided)
     * and expands templates like `EMOTION`.
     * DEPRECATED: Use getNewPrompt() for the new consumption-based flow.
     */
    suspend fun getSmartFallback(mood: String? = null): ReflectionPrompt? = withContext(Dispatchers.IO) {
        syncPromptsFromAsset()

        val entity = if (mood != null) {
            promptDao.get().getOldestPromptByMood(mood) ?: promptDao.get().getOldestPrompt()
        } else {
            promptDao.get().getOldestPrompt()
        }

        entity?.let {
            recordUsage(it.id)
            val domainModel = it.toDomainModel()
            
            // Template Expansion: Inject the current emotion if placeholder exists
            if (mood != null && (domainModel.question.contains("[EMOTION]"))) {
                val expandedQuestion = domainModel.question.replace(
                    "[EMOTION]",
                    mood.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString() }
                )
                domainModel.copy(question = expandedQuestion)
            } else {
                domainModel
            }
        }
    }

    /**
     * Initializes or syncs the database with prompts from JSON asset.
     * Inserts new prompts and updates existing asset prompts while preserving
     * user consumption state (`isConsumed`, `isFavorite`, `usageCount`, `lastUsedTimestamp`).
     */
    suspend fun syncPromptsFromAsset() = withContext(Dispatchers.IO) {
        if (isSynced) return@withContext
        try {
            val jsonString = context.assets.open("prompts.json").bufferedReader().use { it.readText() }
            val assetPrompts = json.decodeFromString<List<ReflectionPrompt>>(jsonString)
            
            val existingPrompts = promptDao.get().getAllPromptsList()
            if (assetPrompts.isEmpty()) {
                if (existingPrompts.isNotEmpty()) {
                    promptDao.get().deletePrompts(existingPrompts)
                }
            } else if (existingPrompts.isEmpty()) {
                promptDao.get().insertPrompts(assetPrompts.map { it.toEntity() })
            } else {
                val existingMap = existingPrompts.associateBy { it.id }
                val assetIds = assetPrompts.map { it.id }.toSet()
                val toInsert = mutableListOf<PromptEntity>()
                val toUpdate = mutableListOf<PromptEntity>()
                val toDelete = existingPrompts.filter { it.id !in assetIds }

                for (assetPrompt in assetPrompts) {
                    val existing = existingMap[assetPrompt.id]
                    if (existing == null) {
                        toInsert.add(assetPrompt.toEntity())
                    } else {
                        // Check if prompt metadata changed, while keeping consumption and usage history
                        if (existing.question != assetPrompt.question ||
                            existing.category != assetPrompt.category ||
                            existing.tone != assetPrompt.tone ||
                            existing.mood != assetPrompt.mood ||
                            existing.depthLevel != assetPrompt.depthLevel
                        ) {
                            toUpdate.add(
                                existing.copy(
                                    question = assetPrompt.question,
                                    category = assetPrompt.category,
                                    tone = assetPrompt.tone,
                                    mood = assetPrompt.mood,
                                    depthLevel = assetPrompt.depthLevel
                                )
                            )
                        }
                    }
                }

                if (toDelete.isNotEmpty()) {
                    promptDao.get().deletePrompts(toDelete)
                }
                if (toInsert.isNotEmpty()) {
                    promptDao.get().insertPrompts(toInsert)
                }
                if (toUpdate.isNotEmpty()) {
                    promptDao.get().updatePrompts(toUpdate)
                }
            }
            isSynced = true
        } catch (e: Exception) {
            Log.e("PromptRepository", "Failed to sync prompts from asset", e)
        }
    }

    /**
     * Compatibility alias for initializeIfEmpty.
     */
    suspend fun initializeIfEmpty() = syncPromptsFromAsset()

    fun getAllPrompts(): Flow<List<ReflectionPrompt>> = 
        promptDao.get().getAllPrompts().map { list -> list.map { it.toDomainModel() } }

    /**
     * Records that a prompt was used to help track variety in the future.
     */
    suspend fun recordUsage(promptId: String) = withContext(Dispatchers.IO) {
        promptDao.get().recordUsage(promptId)
    }
}
