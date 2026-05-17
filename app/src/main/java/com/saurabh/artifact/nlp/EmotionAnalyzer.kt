package com.saurabh.artifact.nlp

import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.model.EmotionResult
import java.util.Locale

/**
 * A privacy-first, on-device NLP engine for emotional tone detection.
 * Uses weighted keyword matching for deterministic and fast execution.
 */
class EmotionAnalyzer {
    
    private val emotionKeywords = mapOf(
        Emotion.HAPPY to listOf("happy", "joy", "glad", "wonderful", "great", "excellent", "love", "amazing", "smile", "blessed", "good", "better"),
        Emotion.SAD to listOf("sad", "unhappy", "cry", "tears", "pain", "hurt", "depressed", "miserable", "sorrow", "grief", "broken", "heartbreak"),
        Emotion.LONELY to listOf("lonely", "alone", "isolated", "empty", "miss", "forgotten", "abandoned", "ignored", "solitude"),
        Emotion.ANXIOUS to listOf("anxious", "worried", "nervous", "scared", "fear", "panic", "stress", "uneasy", "overwhelmed", "pressure", "uncertain"),
        Emotion.ANGRY to listOf("angry", "mad", "furious", "hate", "rage", "annoyed", "frustrated", "bitter", "upset"),
        Emotion.MOTIVATED to listOf("motivated", "inspired", "ready", "excited", "strong", "power", "goal", "achieve", "hope", "future", "possible")
    )

    /**
     * Analyzes input text and returns the most likely emotion with a confidence score.
     */
    fun analyze(text: String): EmotionResult {
        if (text.isBlank()) return EmotionResult(Emotion.NEUTRAL, 0.0f)
        
        // Normalize text: lowercase and remove punctuation
        val normalizedText = text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z\\s]"), " ")
        
        val words = normalizedText.split(Regex("\\s+")).filter { it.isNotBlank() }
        
        if (words.isEmpty()) return EmotionResult(Emotion.NEUTRAL, 0.0f)
        
        val scores = mutableMapOf<Emotion, Float>()

        for (word in words) {
            for ((emotion, keywords) in emotionKeywords) {
                if (keywords.contains(word)) {
                    // Simple linear weighting for now; can be expanded with intensity weights
                    scores[emotion] = (scores[emotion] ?: 0f) + 1.0f
                }
            }
        }

        val topEmotionEntry = scores.maxByOrNull { it.value }
        
        // If no keywords matched, or low signal
        if (topEmotionEntry == null || topEmotionEntry.value == 0f) {
            return EmotionResult(Emotion.NEUTRAL, 0.1f)
        }

        val totalScore = scores.values.sum()
        val confidence = (topEmotionEntry.value / totalScore).coerceIn(0.1f, 1.0f)

        return EmotionResult(topEmotionEntry.key, confidence)
    }
}
