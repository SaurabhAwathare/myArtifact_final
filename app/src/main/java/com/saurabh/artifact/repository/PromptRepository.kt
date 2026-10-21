package com.saurabh.artifact.repository

import android.content.Context
import android.util.Log
import com.saurabh.artifact.data.local.PromptDao
import com.saurabh.artifact.data.local.PromptEntity
import com.saurabh.artifact.data.local.UserConsumedPromptDao
import com.saurabh.artifact.data.local.UserConsumedPromptEntity
import com.saurabh.artifact.data.local.toDomainModel
import com.saurabh.artifact.data.local.toEntity
import com.saurabh.artifact.model.PromptSelectionResult
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.worker.PromptSyncWorker
import androidx.work.ExistingWorkPolicy
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
    private val userConsumedPromptDao: Lazy<UserConsumedPromptDao>,
    @param:ApplicationContext private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val syncMutex = Mutex()
    private var isSynced = false

    @Volatile
    private var approvedPromptsMap: Map<String, ReflectionPrompt> = emptyMap()

    fun isApprovedPromptId(id: String): Boolean {
        return approvedPromptsMap.containsKey(id)
    }

    fun isApprovedPrompt(prompt: ReflectionPrompt): Boolean {
        val canonical = approvedPromptsMap[prompt.id] ?: return false
        return canonical.question == prompt.question
    }

    /**
     * Derives a prompt's batch number from its ID when explicit batch is missing or invalid.
     * Validates ID format (e.g. B1_001 -> 1, B8_250 -> 8).
     * Throws [IllegalArgumentException] if format is invalid.
     */
    fun deriveBatchFromId(id: String, explicitBatch: Int? = null): Int {
        if (explicitBatch != null && explicitBatch in 1..8) {
            return explicitBatch
        }
        if (id.isBlank()) {
            throw IllegalArgumentException("Prompt ID cannot be blank.")
        }
        val regex = Regex("""^B([1-8])_\d+$""")
        val match = regex.matchEntire(id)
            ?: throw IllegalArgumentException("Invalid prompt ID format: '$id'. Expected format 'B<1-8>_<number>' e.g. 'B1_001'.")
        return match.groupValues[1].toInt()
    }

    suspend fun getApprovedPromptById(id: String): ReflectionPrompt? = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext null
        val syncResult = syncPromptsFromAsset()
        if (syncResult.isFailure) return@withContext null
        val canonical = approvedPromptsMap[id] ?: return@withContext null
        val entity = promptDao.get().getPromptById(id)
        if (entity != null) {
            if (entity.question == canonical.question) {
                return@withContext entity.toDomainModel()
            }
        }
        canonical
    }

    /**
     * Selects the next eligible prompt for a specific batch and account [userId].
     * Excludes prompt IDs present in [UserConsumedPromptEntity] for that user.
     * Rejects missing or blank user ID explicitly.
     */
    suspend fun getPromptForBatch(userId: String, batchNumber: Int): PromptSelectionResult = withContext(Dispatchers.IO) {
        if (userId.isBlank()) {
            return@withContext PromptSelectionResult.Error(IllegalArgumentException("User ID must not be blank"))
        }
        if (batchNumber !in 1..8) {
            return@withContext PromptSelectionResult.Error(IllegalArgumentException("Batch number must be between 1 and 8, got $batchNumber"))
        }
        val syncResult = syncPromptsFromAsset()
        if (syncResult.isFailure) {
            return@withContext PromptSelectionResult.Error(
                syncResult.exceptionOrNull() ?: IllegalStateException("Prompt asset synchronization failed")
            )
        }

        val entity = promptDao.get().getNextUnconsumedPromptInBatch(userId, batchNumber)
        if (entity != null) {
            return@withContext PromptSelectionResult.Success(entity.toDomainModel(), batchNumber)
        }

        val totalUnconsumed = promptDao.get().getTotalUnconsumedCount(userId)
        if (totalUnconsumed == 0 && promptDao.get().getPromptCount() > 0) {
            PromptSelectionResult.CatalogExhausted
        } else {
            PromptSelectionResult.BatchExhausted
        }
    }

    /**
     * Advances session progression starting from [startBatchNumber] through 1..8 wrap-around.
     * Returns first unconsumed prompt for [userId], skipping exhausted batches.
     * Returns [PromptSelectionResult.CatalogExhausted] if all catalog prompts are consumed.
     */
    suspend fun getNextPromptInProgression(userId: String, startBatchNumber: Int = 1): PromptSelectionResult = withContext(Dispatchers.IO) {
        if (userId.isBlank()) {
            return@withContext PromptSelectionResult.Error(IllegalArgumentException("User ID must not be blank"))
        }
        if (startBatchNumber !in 1..8) {
            return@withContext PromptSelectionResult.Error(IllegalArgumentException("Start batch number must be between 1 and 8, got $startBatchNumber"))
        }
        val syncResult = syncPromptsFromAsset()
        if (syncResult.isFailure) {
            return@withContext PromptSelectionResult.Error(
                syncResult.exceptionOrNull() ?: IllegalStateException("Prompt asset synchronization failed")
            )
        }

        val totalUnconsumed = promptDao.get().getTotalUnconsumedCount(userId)
        if (totalUnconsumed == 0 && promptDao.get().getPromptCount() > 0) {
            return@withContext PromptSelectionResult.CatalogExhausted
        }

        for (i in 0 until 8) {
            val targetBatch = ((startBatchNumber - 1 + i) % 8) + 1
            val entity = promptDao.get().getNextUnconsumedPromptInBatch(userId, targetBatch)
            if (entity != null) {
                return@withContext PromptSelectionResult.Success(entity.toDomainModel(), targetBatch)
            }
        }

        PromptSelectionResult.CatalogExhausted
    }

    /**
     * Permanently marks a prompt as consumed for [userId] in [UserConsumedPromptEntity].
     * Operation is idempotent via Room IGNORE conflict strategy.
     */
    suspend fun markPromptConsumed(userId: String, promptId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (userId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("User ID must not be blank"))
        }
        if (promptId.isBlank() || promptId.startsWith("nav_")) {
            return@withContext Result.success(Unit)
        }
        val syncResult = syncPromptsFromAsset()
        if (syncResult.isFailure) {
            return@withContext Result.failure(
                syncResult.exceptionOrNull() ?: IllegalStateException("Prompt asset synchronization failed")
            )
        }
        if (!isApprovedPromptId(promptId)) {
            Log.w("PromptRepository", "Ignoring markPromptConsumed for unapproved prompt ID: $promptId")
            return@withContext Result.success(Unit)
        }

        try {
            userConsumedPromptDao.get().markConsumed(
                UserConsumedPromptEntity(
                    userId = userId,
                    promptId = promptId,
                    consumedAt = System.currentTimeMillis(),
                    syncState = "PENDING"
                )
            )
            try {
                PromptSyncWorker.enqueue(context, userId, ExistingWorkPolicy.APPEND_OR_REPLACE)
            } catch (e: Exception) {
                Log.w("PromptRepository", "Failed to enqueue PromptSyncWorker for user $userId", e)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("PromptRepository", "Failed to mark prompt $promptId as consumed for user $userId", e)
            Result.failure(e)
        }
    }

    /**
     * Checks if a prompt has been consumed by [userId].
     */
    suspend fun isPromptConsumed(userId: String, promptId: String): Boolean = withContext(Dispatchers.IO) {
        if (userId.isBlank() || promptId.isBlank()) return@withContext false
        userConsumedPromptDao.get().isPromptConsumed(userId, promptId)
    }

    /**
     * Selects the next eligible unconsumed prompt based on depth level and user progression.
     * Legacy progression method.
     */
    suspend fun getNewPrompt(
        progressionCount: Int,
        excludedPromptId: String? = null
    ): ReflectionPrompt? = withContext(Dispatchers.IO) {
        val syncResult = syncPromptsFromAsset()
        if (syncResult.isFailure) return@withContext null

        val maxDepth = calculateMaxDepth(progressionCount)

        var entity = promptDao.get().getNextEligiblePrompt(maxDepth, excludedPromptId)

        val unconsumedCount = promptDao.get().getUnconsumedCountByDepth(maxDepth)

        if (entity == null && excludedPromptId != null && unconsumedCount > 0) {
            entity = promptDao.get().getNextEligiblePrompt(maxDepth, null)
        }

        if (entity == null && unconsumedCount == 0 && promptDao.get().getPromptCountByDepth(maxDepth) > 0) {
            promptDao.get().resetConsumedPromptsForDepth(maxDepth)
            entity = promptDao.get().getNextEligiblePrompt(maxDepth, excludedPromptId)
                ?: promptDao.get().getNextEligiblePrompt(maxDepth, null)
        }

        val domainModel = entity?.toDomainModel()
        if (domainModel != null && isApprovedPrompt(domainModel)) {
            domainModel
        } else if (domainModel != null) {
            Log.w("PromptRepository", "Unapproved prompt entity selected from DB: ${domainModel.id}")
            null
        } else {
            null
        }
    }

    fun calculateMaxDepth(progressionCount: Int): Int {
        return when {
            progressionCount < 3 -> 1   // 0–2 -> Depth 1
            progressionCount < 10 -> 2  // 3–9 -> Depth 2
            progressionCount < 25 -> 3  // 10–24 -> Depth 3
            else -> 4                   // 25+ -> Depth 4
        }
    }

    suspend fun markAsConsumed(promptId: String) = withContext(Dispatchers.IO) {
        if (promptId.isBlank() || promptId.startsWith("nav_")) return@withContext
        val syncResult = syncPromptsFromAsset()
        if (syncResult.isFailure) return@withContext
        if (!isApprovedPromptId(promptId)) {
            Log.w("PromptRepository", "Ignoring markAsConsumed for unapproved prompt ID: $promptId")
            return@withContext
        }
        promptDao.get().markAsConsumed(promptId)
    }

    suspend fun recycleQuestionBank() = withContext(Dispatchers.IO) {
        promptDao.get().resetConsumedPrompts()
    }

    suspend fun getSmartFallback(mood: String? = null): ReflectionPrompt? = withContext(Dispatchers.IO) {
        val syncResult = syncPromptsFromAsset()
        if (syncResult.isFailure) return@withContext null

        val entity = if (mood != null) {
            promptDao.get().getOldestPromptByMood(mood) ?: promptDao.get().getOldestPrompt()
        } else {
            promptDao.get().getOldestPrompt()
        }

        entity?.let {
            recordUsage(it.id)
            val domainModel = it.toDomainModel()
            
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
     * Synchronizes local database with JSON asset.
     * Repairs metadata (batchNumber, sectionIndex, sectionTitle) while preserving identity
     * and user consumption history.
     */
    suspend fun syncPromptsFromAsset(): Result<Unit> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            if (approvedPromptsMap.isNotEmpty() && isSynced && promptDao.get().getPromptCount() > 0) {
                return@withContext Result.success(Unit)
            }
            try {
                val jsonString = context.assets.open("prompts.json").bufferedReader().use { it.readText() }
                val rawAssetPrompts = json.decodeFromString<List<ReflectionPrompt>>(jsonString)
                if (rawAssetPrompts.isEmpty()) {
                    throw IllegalStateException("Asset 'prompts.json' is empty.")
                }

                val assetPrompts = rawAssetPrompts.map { prompt ->
                    val derivedBatch = deriveBatchFromId(prompt.id, if (prompt.batchNumber > 0) prompt.batchNumber else null)
                    prompt.copy(batchNumber = derivedBatch)
                }

                val existingPrompts = promptDao.get().getAllPromptsList()
                if (existingPrompts.isEmpty()) {
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
                            if (existing.question != assetPrompt.question ||
                                existing.category != assetPrompt.category ||
                                existing.tone != assetPrompt.tone ||
                                existing.mood != assetPrompt.mood ||
                                existing.depthLevel != assetPrompt.depthLevel ||
                                existing.batchNumber != assetPrompt.batchNumber ||
                                existing.sectionIndex != assetPrompt.sectionIndex ||
                                existing.sectionTitle != assetPrompt.sectionTitle
                            ) {
                                toUpdate.add(
                                    existing.copy(
                                        question = assetPrompt.question,
                                        category = assetPrompt.category,
                                        tone = assetPrompt.tone,
                                        mood = assetPrompt.mood,
                                        depthLevel = assetPrompt.depthLevel,
                                        batchNumber = assetPrompt.batchNumber,
                                        sectionIndex = assetPrompt.sectionIndex,
                                        sectionTitle = assetPrompt.sectionTitle
                                    )
                                )
                            }
                        }
                    }

                    if (toDelete.isNotEmpty() || toInsert.isNotEmpty() || toUpdate.isNotEmpty()) {
                        promptDao.get().syncPromptsTransaction(toDelete, toInsert, toUpdate)
                    }
                }

                approvedPromptsMap = assetPrompts.associateBy { it.id }
                isSynced = true
                Result.success(Unit)
            } catch (e: Exception) {
                isSynced = false
                Log.e("PromptRepository", "Failed to sync prompts from asset", e)
                Result.failure(e)
            }
        }
    }

    fun resetSyncState() {
        isSynced = false
    }

    suspend fun initializeIfEmpty() = syncPromptsFromAsset()

    fun getAllPrompts(): Flow<List<ReflectionPrompt>> = 
        promptDao.get().getAllPrompts().map { list -> list.map { it.toDomainModel() } }

    suspend fun recordUsage(promptId: String) = withContext(Dispatchers.IO) {
        promptDao.get().recordUsage(promptId)
    }
}
