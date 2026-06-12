package com.saurabh.artifact.domain.prompt

import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.service.SafetyEvaluator
import com.saurabh.artifact.service.SafetyLevel
import com.saurabh.artifact.service.AdManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PromptResult(
    val prompt: ReflectionPrompt?,
    val safetyLevel: SafetyLevel,
    val isCrisis: Boolean
)

class GetReflectionPromptUseCase @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val safetyEvaluator: SafetyEvaluator,
    private val adManager: AdManager
) {
    suspend operator fun invoke(
        emotion: String?,
        context: String?
    ): PromptResult = withContext(Dispatchers.Default) {
        // 1. Evaluate Safety first (Single Source of Truth)
        val assessment = safetyEvaluator.evaluate(context)
        adManager.updateSafetyContext(assessment)

        // 2. Short-circuit if high risk or crisis detected
        if (assessment.level == SafetyLevel.HIGH || assessment.isCrisis) {
            return@withContext PromptResult(
                prompt = assessment.suggestedPrompt,
                safetyLevel = assessment.level,
                isCrisis = assessment.isCrisis
            )
        }

        // 3. If medium risk with a suggestion, use that instead of generating
        if (assessment.level == SafetyLevel.MEDIUM && assessment.suggestedPrompt != null) {
            return@withContext PromptResult(
                prompt = assessment.suggestedPrompt,
                safetyLevel = assessment.level,
                isCrisis = assessment.isCrisis
            )
        }

        // 4. Otherwise, proceed with AI generation
        val prompt = artifactRepository.getSmartReflectionPrompt(
            emotion = emotion,
            context = context,
            timeOfDay = getTimeOfDayContext()
        )

        PromptResult(
            prompt = prompt,
            safetyLevel = assessment.level,
            isCrisis = assessment.isCrisis
        )
    }

    private fun getTimeOfDayContext(): String {
        return when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Night"
        }
    }
}
