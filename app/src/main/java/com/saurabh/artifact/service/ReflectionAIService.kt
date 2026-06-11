package com.saurabh.artifact.service

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.saurabh.artifact.model.EmotionalTone
import com.saurabh.artifact.model.PromptCategory
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.repository.PromptRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

interface ReflectionAIService {
    suspend fun generatePrompt(
        emotion: String?,
        contextSummary: String?,
        timeOfDay: String?
    ): Result<ReflectionPrompt>
}

@Singleton
class ReflectionAIServiceImpl @Inject constructor(
    private val promptRepository: PromptRepository,
    private val safetyEvaluator: SafetyEvaluator
) : ReflectionAIService {

    companion object {
        private const val TAG = "ReflectionAIService"
        private val GENERATION_TIMEOUT = 8.seconds
        private const val MODEL_NAME = "gemini-1.5-flash"
    }

    private val json = Json { ignoreUnknownKeys = true }

    // Define the JSON schema for Structured Output
    private val promptSchema = Schema.obj(
        mapOf(
            "id" to Schema.string(),
            "question" to Schema.string(),
            "category" to Schema.enumeration(PromptCategory.entries.map { it.name }),
            "tone" to Schema.enumeration(EmotionalTone.entries.map { it.name })
        )
    )

    private val generativeModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = promptSchema
                temperature = 0.7f
            },
            systemInstruction = content {
                text("You are a gentle, empathetic AI guide for a mindful journaling app called 'The Hearth'. " +
                        "Your goal is to generate single, short, reflective questions to help users process their emotions. " +
                        "Avoid being directive, clinical, or overly cheery. Use 'I' sparingly. " +
                        "Ensure the question is open-ended and focused on internal resonance.")
            }
        )
    }

    override suspend fun generatePrompt(
        emotion: String?,
        contextSummary: String?,
        timeOfDay: String?
    ): Result<ReflectionPrompt> {
        return try {
            withTimeout(GENERATION_TIMEOUT) {
                val promptText = buildPrompt(emotion, contextSummary, timeOfDay)
                
                Log.d(TAG, "Generating AI prompt for emotion: $emotion")
                
                val response = generativeModel.generateContent(promptText)
                val jsonResponse = response.text ?: throw Exception("Empty AI response")
                
                Log.v(TAG, "Raw AI JSON: $jsonResponse")

                // 2. Structured Output Parsing
                val aiPrompt = json.decodeFromString<ReflectionPrompt>(jsonResponse)

                // 3. Post-Generation Validation & Guardrails
                val validatedPrompt = aiPrompt.copy(
                    id = "ai_${System.currentTimeMillis()}", // Force unique client-side ID
                    question = safetyEvaluator.filterAIOutput(aiPrompt.question),
                    category = PromptCategory.AI_GUIDED // Force AI category for tracking
                )

                Result.success(validatedPrompt)
            }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            
            Log.e(TAG, "AI generation failed or timed out. Falling back to repository.", e)
            
            // 4. Fallback Logic: Use deterministic prompts if AI fails
            val fallback = promptRepository.getRandomPrompt(emotion)
            if (fallback != null) {
                Result.success(fallback.copy(id = "fallback_${fallback.id}", category = PromptCategory.AI_GUIDED))
            } else {
                Result.success(
                    ReflectionPrompt(
                        id = "fallback_generic",
                        question = "What's resting on your heart in this quiet moment?",
                        category = PromptCategory.GENERAL,
                        tone = EmotionalTone.REFLECTIVE
                    )
                )
            }
        }
    }

    private fun buildPrompt(emotion: String?, context: String?, timeOfDay: String?): String {
        return buildString {
            append("Generate a reflective question for someone ")
            if (emotion != null) append("feeling $emotion ")
            if (timeOfDay != null) append("during the $timeOfDay ")
            append(". ")
            if (!context.isNullOrBlank()) {
                append("Context of their session: $context. ")
            }
            append("Respond ONLY with the JSON format specified.")
        }
    }
}
