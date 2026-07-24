package com.saurabh.artifact.domain.prompt

import com.saurabh.artifact.model.PromptCategory
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.service.ReflectionAIService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the generation and selection of reflection prompts.
 * This manager encapsulates the behavior of context-aware AI prompt generation.
 */
@Singleton
class ReflectionPromptManager @Inject constructor(
    private val aiService: dagger.Lazy<ReflectionAIService>
) {

    /**
     * Generates a contextually relevant reflection prompt using the AI service.
     * Provides a stable fallback prompt if the AI service fails or is unavailable.
     */
    suspend fun getSmartReflectionPrompt(
        emotion: String?,
        context: String?,
        timeOfDay: String?
    ): ReflectionPrompt = withContext(Dispatchers.IO) {
        return@withContext aiService.get().generatePrompt(emotion, context, timeOfDay).getOrElse {
            // Fallback prompt if AI fails
            ReflectionPrompt(
                id = "fallback_${System.currentTimeMillis()}",
                category = PromptCategory.GENERAL,
                question = "What's one thing that stayed with you today?"
            )
        }
    }
}
