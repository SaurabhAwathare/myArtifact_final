package com.saurabh.artifact.domain.prompt

import com.saurabh.artifact.model.PromptSelectionResult
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.service.AdManager
import com.saurabh.artifact.service.SafetyEvaluator
import com.saurabh.artifact.service.SafetyLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PromptResult(
    val prompt: ReflectionPrompt?,
    val safetyLevel: SafetyLevel,
    val isCrisis: Boolean = false,
)

data class SessionPromptResult(
    val selectionResult: PromptSelectionResult,
    val safetyLevel: SafetyLevel,
    val isCrisis: Boolean = false,
)

class GetReflectionPromptUseCase @Inject constructor(
    private val reflectionPromptManager: ReflectionPromptManager,
    private val safetyEvaluator: SafetyEvaluator,
    private val adManager: AdManager,
) {
    /**
     * Account-scoped session prompt selection with safety evaluation.
     */
    suspend fun getPromptForSession(
        userId: String,
        startBatchNumber: Int = 1,
        emotion: String? = null,
        context: String? = null,
    ): SessionPromptResult = withContext(Dispatchers.Default) {
        val assessment = safetyEvaluator.evaluate(listOfNotNull(emotion, context).joinToString(" "))
        adManager.updateSafetyContext(assessment)

        if (assessment.isCrisis) {
            val crisisPrompt = assessment.suggestedPrompt
            return@withContext SessionPromptResult(
                selectionResult = if (crisisPrompt != null) PromptSelectionResult.Success(crisisPrompt, startBatchNumber) else PromptSelectionResult.CatalogExhausted,
                safetyLevel = assessment.level,
                isCrisis = true,
            )
        }

        if ((assessment.level == SafetyLevel.MEDIUM) && (assessment.suggestedPrompt != null)) {
            val suggested = assessment.suggestedPrompt
            return@withContext SessionPromptResult(
                selectionResult = PromptSelectionResult.Success(suggested, startBatchNumber),
                safetyLevel = assessment.level,
                isCrisis = false,
            )
        }

        val selectionResult = reflectionPromptManager.getNextPromptForUser(
            userId = userId,
            startBatchNumber = startBatchNumber
        )

        SessionPromptResult(
            selectionResult = selectionResult,
            safetyLevel = assessment.level,
            isCrisis = false,
        )
    }

    /**
     * Batch-locked prompt selection with safety evaluation.
     * Does not advance session progression or consume the prompt.
     */
    suspend fun getPromptForBatch(
        userId: String,
        batchNumber: Int,
        emotion: String? = null,
        context: String? = null,
    ): SessionPromptResult = withContext(Dispatchers.Default) {
        val assessment = safetyEvaluator.evaluate(listOfNotNull(emotion, context).joinToString(" "))
        adManager.updateSafetyContext(assessment)

        if (assessment.isCrisis) {
            val crisisPrompt = assessment.suggestedPrompt
            return@withContext SessionPromptResult(
                selectionResult = if (crisisPrompt != null) PromptSelectionResult.Success(crisisPrompt, batchNumber) else PromptSelectionResult.CatalogExhausted,
                safetyLevel = assessment.level,
                isCrisis = true,
            )
        }

        if ((assessment.level == SafetyLevel.MEDIUM) && (assessment.suggestedPrompt != null)) {
            val suggested = assessment.suggestedPrompt
            return@withContext SessionPromptResult(
                selectionResult = PromptSelectionResult.Success(suggested, batchNumber),
                safetyLevel = assessment.level,
                isCrisis = false,
            )
        }

        val selectionResult = reflectionPromptManager.getPromptForBatch(
            userId = userId,
            batchNumber = batchNumber
        )

        SessionPromptResult(
            selectionResult = selectionResult,
            safetyLevel = assessment.level,
            isCrisis = false,
        )
    }

    suspend operator fun invoke(
        progressionCount: Int,
        emotion: String?,
        context: String?,
    ): PromptResult = withContext(Dispatchers.Default) {
        // 1. Evaluate Safety first (Single Source of Truth)
        val assessment = safetyEvaluator.evaluate(listOfNotNull(emotion, context).joinToString(" "))
        adManager.updateSafetyContext(assessment)

        // 2. Short-circuit if crisis detected
        if (assessment.isCrisis) {
            return@withContext PromptResult(
                prompt = assessment.suggestedPrompt,
                safetyLevel = assessment.level,
                isCrisis = true,
            )
        }

        // 3. If medium risk with a suggestion, use that instead of generating
        if ((assessment.level == SafetyLevel.MEDIUM) && (assessment.suggestedPrompt != null)) {
            return@withContext PromptResult(
                prompt = assessment.suggestedPrompt,
                safetyLevel = assessment.level,
                isCrisis = false,
            )
        }

        // 4. Otherwise, proceed with prompt selection
        val prompt = reflectionPromptManager.getSmartReflectionPrompt(
            progressionCount = progressionCount,
            emotion = emotion,
            context = context,
            timeOfDay = getTimeOfDayContext(),
        )

        PromptResult(
            prompt = prompt,
            safetyLevel = assessment.level,
            isCrisis = false,
        )
    }

    private fun getTimeOfDayContext(): String {
        return when (java.util.Calendar.getInstance()[java.util.Calendar.HOUR_OF_DAY]) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Night"
        }
    }
}
