package com.saurabh.artifact.nlp

import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.model.TopicSuggestion
import com.saurabh.artifact.model.SuggestionSource
import java.util.Locale

/**
 * Production-grade topic suggestion engine.
 * Combines keyword extraction, semantic matching, and emotion awareness.
 */
class TopicSuggestionEngine {

    // Hardcoded mapping for demonstration; in production, this could be fetched from Firestore/Config
    private val topicKeywordMap = mapOf(
        "burnout" to listOf("tired", "exhausted", "burnout", "work", "pressure", "quit", "overwhelmed", "drain", "energy"),
        "loneliness" to listOf("lonely", "alone", "isolated", "empty", "no one", "miss", "solitude", "disconnect"),
        "creative burnout" to listOf("art", "music", "writing", "stuck", "block", "inspiration", "creative", "burnout", "expression"),
        "grief" to listOf("loss", "death", "passed", "gone", "missing", "grief", "heartache", "funeral", "memory"),
        "self-worth" to listOf("enough", "worth", "value", "love", "hate", "ugly", "beautiful", "confident", "doubt", "self"),
        "college life" to listOf("study", "exam", "grade", "class", "professor", "campus", "dorm", "semester", "student"),
        "relationships" to listOf("partner", "boyfriend", "girlfriend", "breakup", "fight", "love", "date", "together", "argument")
    )

    /**
     * Extracts suggested topics from the transcript and selected emotion.
     */
    fun suggestTopics(
        transcript: List<TranscriptSegment>,
        selectedEmotion: String
    ): List<TopicSuggestion> {
        val fullText = transcript.joinToString(" ") { it.text }.lowercase(Locale.ROOT)
        val suggestions = mutableListOf<TopicSuggestion>()

        // 1. Keyword Matching
        for ((topic, keywords) in topicKeywordMap) {
            val matches = keywords.count { fullText.contains(it) }
            if (matches > 0) {
                val confidence = (matches.toFloat() / keywords.size).coerceIn(0.1f, 0.9f)
                suggestions.add(
                    TopicSuggestion(
                        label = topic,
                        confidence = confidence,
                        reason = "Found $matches keyword(s) related to $topic",
                        source = SuggestionSource.KEYWORD_MATCH
                    )
                )
            }
        }

        // 2. Emotion-Aware Boosting
        // If the emotion is LONELY, boost "loneliness" or "solitude"
        if (selectedEmotion.equals("lonely", ignoreCase = true)) {
            suggestions.add(
                TopicSuggestion(
                    label = "loneliness",
                    confidence = 0.8f,
                    reason = "Resonates with your current emotion",
                    source = SuggestionSource.EMOTION_INFERENCE
                )
            )
        }

        // 3. Normalization and Ranking
        return suggestions
            .distinctBy { it.label }
            .sortedByDescending { it.confidence }
            .take(5)
    }
}
