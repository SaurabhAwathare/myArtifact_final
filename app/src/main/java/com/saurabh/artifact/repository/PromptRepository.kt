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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val syncMutex = Mutex()
    private var isSynced = false

    /**
     * Selects the next eligible unconsumed prompt based on depth level and user progression.
     * Requires an explicit [progressionCount] parameter.
     * Supports optional [excludedPromptId] to prevent immediately repeating a skipped prompt.
     * Automatically triggers depth-scoped Question Bank recycling when all prompts at or below maxDepth are consumed.
     */
    suspend fun getNewPrompt(
        progressionCount: Int,
        excludedPromptId: String? = null
    ): ReflectionPrompt? = withContext(Dispatchers.IO) {
        syncPromptsFromAsset()

        val maxDepth = calculateMaxDepth(progressionCount)

        var entity = promptDao.get().getNextEligiblePrompt(maxDepth, excludedPromptId)

        val unconsumedCount = promptDao.get().getUnconsumedCountByDepth(maxDepth)

        // Fallback 1: If exclusion produced null, check if there's an unconsumed prompt at/below maxDepth without exclusion
        if (entity == null && excludedPromptId != null && unconsumedCount > 0) {
            entity = promptDao.get().getNextEligiblePrompt(maxDepth, null)
        }

        // Exhaustion check: if no unconsumed prompts exist at or below maxDepth, recycle ONLY prompts for maxDepth
        if (entity == null && unconsumedCount == 0 && promptDao.get().getPromptCountByDepth(maxDepth) > 0) {
            promptDao.get().resetConsumedPromptsForDepth(maxDepth)
            entity = promptDao.get().getNextEligiblePrompt(maxDepth, excludedPromptId)
                ?: promptDao.get().getNextEligiblePrompt(maxDepth, null)
        }

        entity?.toDomainModel()
    }

    fun calculateMaxDepth(progressionCount: Int): Int {
        return when {
            progressionCount < 3 -> 1   // 0–2 -> Depth 1
            progressionCount < 10 -> 2  // 3–9 -> Depth 2
            progressionCount < 25 -> 3  // 10–24 -> Depth 3
            else -> 4                   // 25+ -> Depth 4
        }
    }

    /**
     * Marks a prompt as permanently consumed.
     * Synthetic IDs (blank or beginning with "nav_") are ignored to prevent unnecessary DB updates.
     */
    suspend fun markAsConsumed(promptId: String) = withContext(Dispatchers.IO) {
        if (promptId.isBlank() || promptId.startsWith("nav_")) return@withContext
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
        syncMutex.withLock {
            if (isSynced && promptDao.get().getPromptCount() > 0) return@withLock
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
    }

    fun resetSyncState() {
        isSynced = false
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
