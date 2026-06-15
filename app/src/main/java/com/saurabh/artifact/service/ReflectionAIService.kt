package com.saurabh.artifact.service

import android.content.Context
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
import com.saurabh.artifact.util.NetworkUtils
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val safetyEvaluator: SafetyEvaluator,
    private val moderationService: ModerationService,
    @param:ApplicationContext private val context: Context
) : ReflectionAIService {

    companion object {
        private const val TAG = "ReflectionAIService"
        private val GENERATION_TIMEOUT = 15.seconds
        private const val MODEL_NAME = "gemini-2.5-flash"
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
        Firebase.ai(backend = GenerativeBackend.vertexAI()).generativeModel(
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
        // 1. Fail-Fast Connectivity Check
        if (!NetworkUtils.isNetworkAvailable(context)) {
            Log.d(TAG, "Device is offline. Using smart local fallback.")
            val fallback = promptRepository.getSmartFallback(emotion)
            return Result.success(fallback ?: getHardcodedFallback())
        }

        return try {
            withTimeout(GENERATION_TIMEOUT) {
                val promptText = buildPrompt(emotion, contextSummary, timeOfDay)
                
                Log.d(TAG, "Generating AI prompt for emotion: $emotion")
                
                val response = generativeModel.generateContent(promptText)
                val jsonResponse = response.text ?: throw Exception("Empty AI response")
                
                Log.v(TAG, "Raw AI JSON: $jsonResponse")

                // 2. Structured Output Parsing
                val aiPrompt = json.decodeFromString<ReflectionPrompt>(jsonResponse)

                // 3. Post-Generation Validation & Local Moderation Guardrails
                val moderation = moderationService.analyzeLocal(aiPrompt.question)
                if (moderation.isSensitive && (moderation.isCritical || moderation.isSpam)) {
                    Log.w(TAG, "AI generated sensitive or low-quality content: ${moderation.message}")
                    throw Exception("Unsafe AI content detected: ${moderation.message}")
                }

                val validatedPrompt = aiPrompt.copy(
                    id = "ai_${System.currentTimeMillis()}", // Force unique client-side ID
                    question = safetyEvaluator.filterAIOutput(aiPrompt.question),
                    category = PromptCategory.AI_GUIDED // Force AI category for tracking
                )

                Result.success(validatedPrompt)
            }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            
            val reason = if (e is TimeoutCancellationException) "Timeout" else "Error"
            Log.e(TAG, "AI generation failed ($reason). Falling back to repository.", e)
            
            // 4. Fallback Logic: Use smart local fallback if AI fails or times out
            val fallback = promptRepository.getSmartFallback(emotion)
            Result.success(fallback ?: getHardcodedFallback())
        }
    }

    private fun getHardcodedFallback(): ReflectionPrompt {
        return ReflectionPrompt(
            id = "fallback_generic",
            question = "What's resting on your heart in this quiet moment?",
            category = PromptCategory.GENERAL,
            tone = EmotionalTone.REFLECTIVE
        )
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
