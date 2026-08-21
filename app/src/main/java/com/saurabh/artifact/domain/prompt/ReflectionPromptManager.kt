package com.saurabh.artifact.domain.prompt

import com.saurabh.artifact.model.PromptCategory
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.repository.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the selection of reflection prompts from the local question bank.
 * This manager ensures that prompts follow the non-repetition and depth-progression rules.
 */
@Singleton
class ReflectionPromptManager @Inject constructor(
    private val promptRepository: PromptRepository
) {

    /**
     * Fetches the next eligible reflection prompt from the local question bank.
     * Guarantees zero AI cost and works fully offline.
     */
    suspend fun getNextPrompt(): ReflectionPrompt = withContext(Dispatchers.IO) {
        return@withContext promptRepository.getNewPrompt() ?: ReflectionPrompt(
            id = "fallback_${System.currentTimeMillis()}",
            category = PromptCategory.GENERAL,
            question = "What's one thing that stayed with you today?",
            depthLevel = 1
        )
    }

    /**
     * Legacy bridge for existing callers. Delegates to getNextPrompt().
     */
    suspend fun getSmartReflectionPrompt(
        emotion: String? = null,
        context: String? = null,
        timeOfDay: String? = null
    ): ReflectionPrompt = getNextPrompt()
}
