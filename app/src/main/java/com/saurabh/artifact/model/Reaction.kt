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
enum class ReactionType(val id: String, val label: String, val atmosphericLabel: String, val emoji: String, val semanticValue: Float) {
    I_HEAR_YOU("i_hear_you", "I hear you", "Someone stayed with you quietly", "🫂", 1.0f),
    SENDING_STRENGTH("sending_strength", "Sending strength", "Strength was sent your way", "💫", 1.2f),
    RELATABLE("relatable", "Relatable", "Your words found a home in someone", "🐚", 0.9f),
    STAY_STRONG("stay_strong", "Stay strong", "Someone is holding a candle for you", "🕯️", 1.1f),
    HOLDING_SPACE("holding_space", "Holding space", "Someone is holding space for you", "🕯️", 1.0f),
    THANK_YOU("thank_you", "Thank you", "A quiet thank-you was left behind", "🙏", 0.8f),
    FELT_DEEPLY("felt_deeply", "Felt deeply", "This rippled deeply through a heart", "🌊", 1.3f),
    RESPECTFUL_DISAGREEMENT("respectfully_disagree", "Respectfully disagree", "Someone sees this differently, gently", "🧘", 0.5f);

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
    VISIBLE,        // Gentle wording with count
    APPROXIMATE,    // "A few people felt this"
    CREATOR_ONLY,   // Only the author sees counts (publicly HIDDEN)
    HIDDEN          // No counts shown at all
}

@Serializable
data class ArtifactReaction(
    var id: String = "",
    var artifactId: String = "",
    var userId: String = "", // Used for private state (Pulse)
    @get:PropertyName("type")
    @set:PropertyName("type")
    var typeId: String = "i_hear_you",
    var createdAt: Timestamp = Timestamp.now(),
    var isPrivatePulse: Boolean = true // Flag to distinguish from legacy/public reactions
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
    var lastUpdated: Timestamp = Timestamp.now(),
    var echoVersion: Int = 1 // Track version of the aggregation logic
) {
    fun getFuzzySummary(isOwner: Boolean = false): String {
        if (visibility == ReactionVisibilityMode.HIDDEN) return ""
        if (visibility == ReactionVisibilityMode.CREATOR_ONLY && !isOwner) return ""
        
        return when (visibility) {
            ReactionVisibilityMode.VISIBLE -> {
                when {
                    totalCount <= 0 -> ""
                    totalCount == 1 -> "Another soul felt this"
                    else -> "$totalCount souls felt this too"
                }
            }
            else -> { // APPROXIMATE or CREATOR_ONLY (if owner)
                when {
                    totalCount <= 0 -> ""
                    totalCount == 1 -> "Another soul felt this"
                    totalCount in 2..5 -> "A few people are holding space here"
                    totalCount in 6..20 -> "Many have found resonance in your words"
                    else -> "A vast echo is returning to you"
                }
            }
        }
    }
}

enum class FeedbackType(val label: String, val description: String) {
    NOT_FOR_ME("Not for me", "I'd like to see less of this"),
    TOO_INTENSE("Too intense", "This content feels overwhelming"),
    REPETITIVE("Repetitive", "I've heard similar things recently"),
    SAFETY_CONCERN("Safety concern", "I'm worried about the author's wellbeing")
}
