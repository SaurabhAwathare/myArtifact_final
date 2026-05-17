@file:UseSerializers(TimestampSerializer::class)

package com.saurabh.artifact.model

import com.google.firebase.Timestamp
import com.saurabh.artifact.util.TimestampSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * PRODUCTION-GRADE MODEL: Hidden Comments System
 * Designed for "Respond to the artifact, not the crowd."
 */

@Serializable
enum class CommentVisibilityMode {
    PUBLIC,           // Standard visibility
    HIDDEN,           // Private between author and creator
    CREATOR_ONLY,     // Author cannot see it after posting (journal style)
    REFLECTION_DELAY, // Revealed to author/others after X hours
    MUTUAL_UNLOCK     // Visible only if both users have responded
}

@Serializable
enum class CommentModerationState {
    PENDING,
    APPROVED,
    FLAGGED,
    BLOCKED
}

@Serializable
data class ArtifactComment(
    val id: String = "",
    val artifactId: String = "",
    val authorId: String = "",
    val authorDisplayName: String? = null,
    val authorEmoji: String = "✨",
    val content: String = "",
    val audioUrl: String? = null,
    val visibility: CommentVisibilityMode = CommentVisibilityMode.HIDDEN,
    val emotionalMarkers: List<String> = emptyList(),
    val moderationState: CommentModerationState = CommentModerationState.PENDING,
    val creatorReaction: ReactionType? = null,
    val createdAt: Timestamp = Timestamp.now(),
    val revealAt: Timestamp? = null,
    val isAnonymous: Boolean = false
) {
    fun toVoiceComment(): VoiceComment = VoiceComment(
        id = id,
        authorId = authorId,
        authorName = authorDisplayName ?: "Anonymous Soul",
        authorEmoji = authorEmoji,
        audioUrl = audioUrl ?: "",
        creatorReaction = creatorReaction,
        createdAt = createdAt
    )
}

/**
 * Aggregated emotional insights for the creator.
 * Used in the "Hearth" view to provide a calm summary.
 */
@Serializable
data class EmotionalResponseSummary(
    val artifactId: String = "",
    val totalResponses: Int = 0,
    val topEmotions: Map<String, Int> = emptyMap(),
    val resonanceScore: Float = 0f,
    val lastUpdated: Timestamp = Timestamp.now(),
    val aiInsight: String? = null
)

/**
 * Configuration for how comments behave on a specific artifact.
 */
@Serializable
data class ArtifactCommentSettings(
    val artifactId: String = "",
    val defaultVisibility: CommentVisibilityMode = CommentVisibilityMode.HIDDEN,
    val isCommentingEnabled: Boolean = true,
    val requireCompletionToComment: Boolean = true,
    val revealDelayHours: Int = 0
)
