package com.saurabh.artifact.model

import kotlinx.serialization.Serializable

/**
 * Represents a specific topic tag that can be attached to an artifact.
 */
@Serializable
data class TopicTag(
    val id: String = "",
    val label: String,
    val categoryId: String? = null,
    val usageCount: Int = 0,
    val isSystemGenerated: Boolean = false,
    val moderationState: TopicModerationState = TopicModerationState.APPROVED,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Broad categories for topics to help with organization and filtering.
 */
@Serializable
data class TopicCategory(
    val id: String = "",
    val name: String,
    val description: String,
    val iconEmoji: String = "✨",
    val colorHex: String = "#FFB300"
)

enum class SuggestionSource {
    AI_EMBEDDING,
}

/**
 * Moderation state for user-created topics.
 */
@Serializable
enum class TopicModerationState {
    APPROVED,
}
