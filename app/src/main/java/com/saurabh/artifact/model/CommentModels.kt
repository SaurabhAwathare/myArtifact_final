@file:UseSerializers(TimestampSerializer::class)

package com.saurabh.artifact.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.PropertyName
import com.saurabh.artifact.util.TimestampSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * PRODUCTION-GRADE MODEL: Hidden Comments System
 * Designed for "Respond to the artifact, not the crowd."
 */

@Serializable
enum class VisibilityLayer {
    SANCTUARY, // Private to author only
    BRIDGE,    // Private between author and creator
    RESONANCE  // Shared with other listeners who have unlocked the artifact
}

@Serializable
enum class AuthorType {
    PSEUDONYM,      // Uses the user's chosen pseudonym
    QUIET_PRESENCE  // Complete anonymity
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
    val authorId: String = "", // Internal UID
    val artifactOwnerId: String = "", // Cache for security rule lookups
    val authorAnonymousName: String? = null,
    val authorAvatarSeed: String = "",
    val content: String = "",
    val visibilityLayer: VisibilityLayer = VisibilityLayer.BRIDGE,
    val authorType: AuthorType = AuthorType.PSEUDONYM,
    val emotionalMarkers: List<String> = emptyList(),
    val moderationState: CommentModerationState = CommentModerationState.PENDING,
    @get:Exclude @set:Exclude
    var creatorReaction: ReactionType? = null,
    val createdAt: Timestamp = Timestamp.now(),
    val revealAt: Timestamp? = null,
) {
    @get:PropertyName("creatorReaction")
    @set:PropertyName("creatorReaction")
    var creatorReactionString: String?
        get() = creatorReaction?.id
        set(value) {
            creatorReaction = value?.let { ReactionType.fromId(it) }
        }
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
    val defaultVisibility: VisibilityLayer = VisibilityLayer.BRIDGE,
    val isCommentingEnabled: Boolean = true,
    val requireCompletionToComment: Boolean = true,
    val revealDelayHours: Int = 0
)
