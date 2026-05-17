package com.saurabh.artifact.security

import com.saurabh.artifact.model.TopicModerationState

/**
 * Privacy-first moderation service for topic tagging and artifact metadata.
 */
class ModerationService {

    private val bannedWords = listOf("spam", "offensive_word_1", "offensive_word_2") // Placeholder
    private val piiPatterns = listOf(
        Regex("\\d{10}"), // Simple phone number
        Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"), // Email
        Regex("\\d+\\s+[a-zA-Z]+\\s+St|Ave|Rd") // Simple address
    )

    /**
     * Checks if a custom topic label is safe to use.
     */
    fun checkTopicLabel(label: String): TopicModerationState {
        val normalized = label.lowercase().trim()
        
        // 1. Check banned words
        if (bannedWords.any { normalized.contains(it) }) {
            return TopicModerationState.REJECTED
        }

        // 2. Check PII
        if (piiPatterns.any { it.containsMatchIn(normalized) }) {
            return TopicModerationState.FLAGGED_FOR_REVIEW
        }

        // 3. Length check
        if (normalized.length < 2 || normalized.length > 30) {
            return TopicModerationState.REJECTED
        }

        return TopicModerationState.APPROVED
    }

    /**
     * Identifies if a topic is "vulnerable" and requires supportive discovery logic.
     */
    fun isVulnerableTopic(topicLabel: String): Boolean {
        val vulnerable = listOf("grief", "trauma", "self-harm", "abuse", "suicide")
        return vulnerable.any { topicLabel.contains(it, ignoreCase = true) }
    }
}
