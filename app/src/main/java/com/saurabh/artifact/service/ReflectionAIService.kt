package com.saurabh.artifact.service

import com.saurabh.artifact.model.EmotionalTone
import com.saurabh.artifact.model.PromptCategory
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.model.ReflectionPromptProvider
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

interface ReflectionAIService {
    suspend fun generatePrompt(
        emotion: String?,
        contextSummary: String?,
        timeOfDay: String?
    ): Result<ReflectionPrompt>
}

@Singleton
class ReflectionAIServiceImpl @Inject constructor(
    private val safetyEvaluator: SafetyEvaluator
) : ReflectionAIService {
    
    override suspend fun generatePrompt(
        emotion: String?,
        contextSummary: String?,
        timeOfDay: String?
    ): Result<ReflectionPrompt> {
        return try {
            // 1. Check for immediate safety concerns in context summary (if provided)
            contextSummary?.let {
                val assessment = safetyEvaluator.evaluate(it)
                if (assessment.level != SafetyLevel.LOW && assessment.suggestedPrompt != null) {
                    return Result.success(assessment.suggestedPrompt)
                }
            }

            // Simulate network latency for AI generation
            delay(1500)
            
            // 2. Logic to simulate AI-driven context awareness
            val rawPromptText = when (emotion) {
                "Anxiety" -> "What is one thing you can touch, see, and hear right now to ground yourself?"
                "Grief" -> "If you could send a gentle message to your past self today, what would it say?"
                "Joy" -> "How can you ripple this feeling of joy out to someone else today?"
                "Loneliness" -> "What is a small way you can be a good companion to yourself this evening?"
                "Peace" -> "What part of your environment is reflecting this peace back to you?"
                else -> {
                    if (timeOfDay == "Morning") {
                        "What is your primary intention for how you want to show up today?"
                    } else {
                        ReflectionPromptProvider.getRandomPrompt().question
                    }
                }
            }

            // 3. Apply safety filters to the AI output
            val safePromptText = safetyEvaluator.filterAIOutput(rawPromptText)

            Result.success(
                ReflectionPrompt(
                    id = "ai_${System.currentTimeMillis()}",
                    question = safePromptText,
                    category = PromptCategory.AI_GUIDED,
                    tone = EmotionalTone.fromString(emotion ?: "Reflective")
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
