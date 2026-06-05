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
        val assessment = safetyEvaluator.evaluate(context)
        adManager.updateSafetyContext(assessment)

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
