package com.saurabh.artifact.nlp

import com.saurabh.artifact.model.ConversationStyle
import com.saurabh.artifact.model.StyleSuggestion
import java.util.Locale
import javax.inject.Inject

/**
 * On-device analyzer for detecting conversational style and energy.
 * Uses weighted keyword analysis and structural patterns to suggest styles.
 */
class ConversationStyleDetector @Inject constructor() {

    private val stylePatterns = mapOf(
        ConversationStyle.STORYTELLING to listOf(
            "once upon a time", "so then", "happened", "next thing", "suddenly", 
            "remember when", "started with", "finally", "outcome"
        ),
        ConversationStyle.REFLECTIVE to listOf(
            "i wonder", "maybe", "perhaps", "thinking about", "realized", 
            "makes me feel", "why did", "trying to understand", "question"
        ),
        ConversationStyle.RANT to listOf(
            "can't believe", "so annoyed", "sick of", "honestly", "just", 
            "ridiculous", "frustrating", "always happens", "pointless"
        ),
        ConversationStyle.FUNNY to listOf(
            "hilarious", "laugh", "funny", "joke", "lol", "kidding", 
            "amazing", "incredible", "can't make this up"
        ),
        ConversationStyle.COMFORT to listOf(
            "it's okay", "here for you", "vulnerable", "soft", "peace", 
            "safe", "warmth", "gentle", "sharing this because"
        ),
        ConversationStyle.LATE_NIGHT to listOf(
            "quiet", "midnight", "late", "can't sleep", "whispering", 
            "solitude", "dark", "calm", "peaceful"
        ),
        ConversationStyle.ADVICE to listOf(
            "should", "try", "recommend", "lesson", "learned", 
            "advice", "perspective", "helpful", "important to"
        )
    )

    /**
     * Analyzes the transcript and returns a list of suggested styles with confidence scores.
     */
    fun detectStyles(transcript: String): List<StyleSuggestion> {
        if (transcript.isBlank()) return emptyList()

        val normalized = transcript.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z\\s]"), " ")
        
        val words = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()

        val styleScores = mutableMapOf<ConversationStyle, Float>()

        // 1. Keyword Matching
        for ((style, keywords) in stylePatterns) {
            for (keyword in keywords) {
                if (normalized.contains(keyword)) {
                    styleScores[style] = (styleScores[style] ?: 0f) + 1.5f
                }
            }
        }

        // 2. Structural Patterns
        // Stream of consciousness often has many "and", "um", "like" (if captured)
        val fillerCount = words.count { it in listOf("and", "like", "so", "um", "uh") }
        if (fillerCount.toFloat() / words.size > 0.15f) {
            styleScores[ConversationStyle.STREAM_OF_CONSCIOUSNESS] = 2.0f
        }

        // Chaotic often has shorter sentences or many exclamation signals (if available)
        // For now, we use a simple variety/repetition metric
        val uniqueWords = words.toSet().size
        val varietyRatio = uniqueWords.toFloat() / words.size
        if (varietyRatio < 0.4f && words.size > 50) {
            styleScores[ConversationStyle.CHAOTIC] = 1.5f
        }

        // Normalize scores into confidence
        val totalScore = styleScores.values.sum()
        if (totalScore == 0f) return emptyList()

        return styleScores.map { (style, score) ->
            StyleSuggestion(
                style = style,
                confidence = (score / totalScore).coerceIn(0.1f, 1.0f),
                reasoning = "Matches conversational patterns found in your transcript."
            )
        }.sortedByDescending { it.confidence }.take(3)
    }
}
