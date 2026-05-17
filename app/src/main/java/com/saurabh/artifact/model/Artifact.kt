package com.saurabh.artifact.model

import com.google.firebase.Timestamp
import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

enum class Visibility {
    PUBLIC, PRIVATE
}

enum class ArtifactStatus {
    DRAFT
}

@Immutable
data class Artifact(
    val id: String = "",
    val userId: String = "",
    val username: String = "", // This should be the anonymousName
    val avatarColor: String = "#FFD700",
    val audioUrl: String = "",
    val createdAt: Timestamp = Timestamp.now(),
    val isPublic: Boolean = true, // Legacy field, consider migration
    val visibility: Visibility = Visibility.PUBLIC,
    val status: ArtifactStatus = ArtifactStatus.DRAFT,
    val isDraft: Boolean = false,
    val duration: Long = 0,
    val title: String = "",
    val reactions: Map<String, Int> = emptyMap(),
    val emotion: String = "",
    val emotionTag: String = "",
    val emotionConfidence: Float = 0f,
    val prompt: String = "",
    val playCount: Int = 0,
    val reactionCount: Int = 0,
    val userEmoji: String = "✨",
    val avatarConfigJson: String? = null,
    val commentCount: Int = 0,
    val moderationStatus: String = "CLEAN",
    val toxicityScore: Float = 0f,
    val reportCount: Int = 0,
    val reporterIds: List<String> = emptyList(),
    val transcript: List<TranscriptSegment> = emptyList(),
    val amplitudeData: List<Float> = emptyList(),
    val flaggedSegments: List<FlaggedSegment> = emptyList(),
    val moderation: ModerationMetadata = ModerationMetadata(),
    val conversationMetadata: ArtifactConversationMetadata = ArtifactConversationMetadata(),
    val reactionVisibility: ReactionVisibilityMode = ReactionVisibilityMode.APPROXIMATE
) {
    /**
     * Returns a lightweight version of the artifact suitable for large lists (feed).
     * Strips heavy lists like transcript and original amplitude data to stay under Binder limits.
     */
    fun slimForFeed(): Artifact {
        return this.copy(
            transcript = emptyList(),
            amplitudeData = if (amplitudeData.size > 64) amplitudeData.take(64) else amplitudeData,
            flaggedSegments = emptyList(),
            reporterIds = emptyList()
        )
    }
}

@Immutable
@Serializable
data class TranscriptSegment(
    val id: String = "",
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
    val words: List<WordToken> = emptyList()
) {
    /**
     * Removes per-word timing to save memory when only the segment text is needed.
     */
    fun slim(): TranscriptSegment = this.copy(words = emptyList())
}

@Immutable
@Serializable
data class WordToken(
    val word: String,
    val startMs: Long,
    val endMs: Long
)

@Immutable
@Serializable
data class FlaggedSegment(
    val id: String = "",
    val type: PiiType, // e.g. "PHONE"
    val startMs: Long,
    val endMs: Long,
    val originalText: String,
    val confidence: Float,
    val isRedacted: Boolean = true,
    val userDecision: String = "PENDING" // "REDACT", "KEEP", "PENDING"
)

@Serializable
enum class PiiType {
    NAME, LOCATION, PHONE, EMAIL, ID_NUMBER, OTHER
}

/**
 * Heavy data container for Artifacts, loaded only when needed (e.g., detail view or expansion).
 */
@Immutable
data class ArtifactDetail(
    val id: String = "",
    val amplitudeData: List<Float> = emptyList(),
    val comments: List<VoiceComment> = emptyList(),
    val reactionCounts: ArtifactReactionCounts? = null
)

@Immutable
data class VoiceComment(
    val id: String = "",
    val authorId: String = "",
    val authorName: String = "Anonymous Soul",
    val authorEmoji: String = "✨",
    val authorAvatarConfigJson: String? = null,
    val audioUrl: String = "",
    val creatorReaction: ReactionType? = null,
    val createdAt: Timestamp = Timestamp.now()
)

data class Reply(
    val id: String = "",
    val artifactId: String = "",
    val message: String = "",
    val createdAt: Timestamp = Timestamp.now()
)

data class NotificationItem(
    val id: String = "",
    val userId: String = "",
    val message: String = "Someone replied to your artifact 💬",
    val artifactId: String = "",
    val createdAt: Timestamp = Timestamp.now(),
    val isRead: Boolean = false
)
