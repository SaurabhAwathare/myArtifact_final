package com.saurabh.artifact.service

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModerationService @Inject constructor() {

    private val toxicPatterns = listOf(
        Regex("\\b(hate|stupid|idiot|loser|ugly|trash|garbage)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(kill|die|murder|hurt|beat|attack)\\b", RegexOption.IGNORE_CASE),
    )

    private val harassmentPatterns = listOf(
        Regex("\\byou are (a|an) (.*)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bi hate you\\b", RegexOption.IGNORE_CASE)
    )

    private val spamPatterns = listOf(
        Regex("(.)\\1{4,}", RegexOption.IGNORE_CASE), // Character flooding (e.g., "aaaaa")
        Regex("(\\b\\w+\\b)(.*\\b\\1\\b){3,}", RegexOption.IGNORE_CASE) // Word repetition
    )

    /**
     * Performs a lightweight local check for pre-publish nudges.
     * This is NOT the final moderation check, which happens on the server.
     */
    fun analyzeLocal(text: String): ModerationAnalysis {
        val normalized = text.lowercase().trim()
        
        // 1. Quality Check (Too Short)
        val words = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        if ((words.size < 3) && words.isNotEmpty()) {
            return ModerationAnalysis(
                isSensitive = true,
                message = "The Hearth values depth. Could you share a bit more of your heart?",
                isLowQuality = true
            )
        }

        // 2. Spam Detection
        val isSpam = spamPatterns.any { it.containsMatchIn(normalized) }
        if (isSpam) {
            return ModerationAnalysis(
                isSensitive = true,
                message = "This looks like it might be repetitive. Let's keep the resonance clear.",
                isCritical = true,
                isSpam = true
            )
        }

        // 3. Harassment
        val containsHarassment = harassmentPatterns.any { it.containsMatchIn(normalized) }
        if (containsHarassment) {
            return ModerationAnalysis(
                isSensitive = true,
                message = "Our community is built on kindness. Please review your message.",
                isCritical = true
            )
        }

        // 4. Toxicity
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
    val isCritical: Boolean = false,
    val isSpam: Boolean = false,
    val isLowQuality: Boolean = false
)
