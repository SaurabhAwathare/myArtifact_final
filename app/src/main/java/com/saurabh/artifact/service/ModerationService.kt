package com.saurabh.artifact.service

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModerationService @Inject constructor() {

    private val toxicPatterns = listOf(
        Regex("\\b(hate|stupid|idiot|loser|ugly|trash|garbage)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(kill|die|murder|hurt|beat|attack)\\b", RegexOption.IGNORE_CASE)
    )

    private val harassmentPatterns = listOf(
        Regex("\\byou are (a|an) (.*)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bi hate you\\b", RegexOption.IGNORE_CASE)
    )

    /**
     * Performs a lightweight local check for pre-publish nudges.
     * This is NOT the final moderation check, which happens on the server.
     */
    fun analyzeLocal(text: String): ModerationAnalysis {
        val normalized = text.lowercase()
        
        val containsHarassment = harassmentPatterns.any { it.containsMatchIn(normalized) }
        if (containsHarassment) {
            return ModerationAnalysis(
                isSensitive = true,
                message = "Our community is built on kindness. Please review your message.",
                isCritical = true
            )
        }

        val containsToxicity = toxicPatterns.any { it.containsMatchIn(normalized) }
        if (containsToxicity) {
            return ModerationAnalysis(
                isSensitive = true,
                message = "This might be sensitive for some. Is this how you want to share your heart?",
                isCritical = false
            )
        }

        return ModerationAnalysis(isSensitive = false)
    }
}

data class ModerationAnalysis(
    val isSensitive: Boolean,
    val message: String = "",
    val isCritical: Boolean = false
)
