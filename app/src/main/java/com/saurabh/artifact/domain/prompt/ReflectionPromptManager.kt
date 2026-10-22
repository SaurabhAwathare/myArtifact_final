package com.saurabh.artifact.domain.prompt

import com.saurabh.artifact.model.PromptSelectionResult
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.repository.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the selection of reflection prompts from the local question bank.
 * Phase 2 implementation provides account-scoped batch selection and session progression.
 */
@Singleton
class ReflectionPromptManager @Inject constructor(
    private val promptRepository: PromptRepository
) {

    /**
     * Fetches the next prompt for a user according to the batch progression flow (1 -> 2 -> ... -> 8 -> 1).
     * Rejects blank [userId].
     */
    suspend fun getNextPromptForUser(
        userId: String,
        startBatchNumber: Int = 1
    ): PromptSelectionResult = withContext(Dispatchers.IO) {
        promptRepository.getNextPromptInProgression(userId, startBatchNumber)
    }

    /**
     * Fetches an eligible unconsumed prompt for a specific batch and account [userId].
     * Does not advance session progression or consume the prompt.
     */
    suspend fun getPromptForBatch(
        userId: String,
        batchNumber: Int
    ): PromptSelectionResult = withContext(Dispatchers.IO) {
        promptRepository.getPromptForBatch(userId, batchNumber)
    }

    /**
     * Permanently marks a prompt as consumed for the specified [userId].
     */
    suspend fun markPromptConsumed(
        userId: String,
        promptId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        promptRepository.markPromptConsumed(userId, promptId)
    }

    /**
     * Checks if a prompt has been consumed by [userId].
     */
    suspend fun isPromptConsumed(
        userId: String,
        promptId: String
    ): Boolean = withContext(Dispatchers.IO) {
        promptRepository.isPromptConsumed(userId, promptId)
    }

    /**
     * Fetches the next eligible reflection prompt from the local question bank.
     * Legacy depth-based method.
     */
    suspend fun getNextPrompt(
        progressionCount: Int,
        excludedPromptId: String? = null
    ): ReflectionPrompt? = withContext(Dispatchers.IO) {
        return@withContext promptRepository.getNewPrompt(progressionCount, excludedPromptId)
    }

    /**
     * Legacy bridge for existing callers. Delegates to getNextPrompt().
     */
    suspend fun getSmartReflectionPrompt(
        progressionCount: Int,
        emotion: String? = null,
        context: String? = null,
        timeOfDay: String? = null
    ): ReflectionPrompt? = getNextPrompt(progressionCount = progressionCount)
}
