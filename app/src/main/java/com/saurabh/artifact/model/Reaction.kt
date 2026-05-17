@file:UseSerializers(TimestampSerializer::class)

package com.saurabh.artifact.model

import com.google.firebase.Timestamp
import com.saurabh.artifact.util.TimestampSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * EMOTIONAL REACTION TAXONOMY
 * Designed for resonance, shared understanding, and quiet support.
 */
@Serializable
enum class ReactionType(val label: String, val emoji: String, val semanticValue: Float) {
    I_HEAR_YOU("I hear you", "🫂", 1.0f),
    SENDING_STRENGTH("Sending strength", "💫", 1.2f),
    RELATABLE("Relatable", "🐚", 0.9f),
    STAY_STRONG("Stay strong", "🕯️", 1.1f),
    HOLDING_SPACE("Holding space", "🕯️", 1.0f),
    THANK_YOU("Thank you", "🙏", 0.8f),
    FELT_DEEPLY("Felt deeply", "🌊", 1.3f),
    RESPECTFUL_DISAGREEMENT("Respectfully disagree", "🧘", 0.5f);

    companion object {
        fun fromId(id: String): ReactionType = entries.find { it.name == id } ?: I_HEAR_YOU
    }
}

@Serializable
enum class ReactionVisibilityMode {
    VISIBLE,        // Exact counts shown (gentle wording)
    APPROXIMATE,    // "A few people felt this"
    CREATOR_ONLY,   // Only the author sees counts
    HIDDEN          // No counts shown publicly
}

@Serializable
data class ArtifactReaction(
    val id: String = "",
    val artifactId: String = "",
    val userId: String = "",
    val type: ReactionType = ReactionType.I_HEAR_YOU,
    val createdAt: Timestamp = Timestamp.now()
)

@Serializable
data class ArtifactReactionCounts(
    val artifactId: String = "",
    val totalCount: Int = 0,
    val breakdown: Map<String, Int> = emptyMap(),
    val visibility: ReactionVisibilityMode = ReactionVisibilityMode.APPROXIMATE,
    val aiSummary: String? = null,
    val lastUpdated: Timestamp = Timestamp.now()
)

enum class FeedbackType(val label: String, val description: String) {
    NOT_FOR_ME("Not for me", "I'd like to see less of this"),
    TOO_INTENSE("Too intense", "This content feels overwhelming"),
    REPETITIVE("Repetitive", "I've heard similar things recently"),
    SAFETY_CONCERN("Safety concern", "I'm worried about the author's wellbeing")
}
