@file:UseSerializers(TimestampSerializer::class)

package com.saurabh.artifact.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.PropertyName
import com.saurabh.artifact.util.TimestampSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * EMOTIONAL REACTION TAXONOMY
 * Designed for resonance, shared understanding, and quiet support.
 */
@Serializable
enum class ReactionType(val id: String, val label: String, val emoji: String, val semanticValue: Float) {
    I_HEAR_YOU("i_hear_you", "I hear you", "🫂", 1.0f),
    SENDING_STRENGTH("sending_strength", "Sending strength", "💫", 1.2f),
    RELATABLE("relatable", "Relatable", "🐚", 0.9f),
    STAY_STRONG("stay_strong", "Stay strong", "🕯️", 1.1f),
    HOLDING_SPACE("holding_space", "Holding space", "🕯️", 1.0f),
    THANK_YOU("thank_you", "Thank you", "🙏", 0.8f),
    FELT_DEEPLY("felt_deeply", "Felt deeply", "🌊", 1.3f),
    RESPECTFUL_DISAGREEMENT("respectfully_disagree", "Respectfully disagree", "🧘", 0.5f);

    companion object {
        fun fromId(id: String): ReactionType {
            return entries.find { 
                it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) 
            } ?: I_HEAR_YOU
        }
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
    var id: String = "",
    var artifactId: String = "",
    var userId: String = "",
    @get:PropertyName("type")
    @set:PropertyName("type")
    var typeId: String = "i_hear_you",
    var createdAt: Timestamp = Timestamp.now()
) {
    @get:Exclude @set:Exclude
    var type: ReactionType
        get() = ReactionType.fromId(typeId)
        set(value) {
            typeId = value.id
        }
}

@Serializable
data class ArtifactReactionCounts(
    var artifactId: String = "",
    var totalCount: Int = 0,
    @get:PropertyName("breakdown")
    @set:PropertyName("breakdown")
    var breakdown: Map<String, Int> = mutableMapOf(),
    var visibility: ReactionVisibilityMode = ReactionVisibilityMode.APPROXIMATE,
    var aiSummary: String? = null,
    var lastUpdated: Timestamp = Timestamp.now()
)

enum class FeedbackType(val label: String, val description: String) {
    NOT_FOR_ME("Not for me", "I'd like to see less of this"),
    TOO_INTENSE("Too intense", "This content feels overwhelming"),
    REPETITIVE("Repetitive", "I've heard similar things recently"),
    SAFETY_CONCERN("Safety concern", "I'm worried about the author's wellbeing")
}
