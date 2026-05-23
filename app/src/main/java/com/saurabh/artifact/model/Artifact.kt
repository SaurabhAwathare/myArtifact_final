package com.saurabh.artifact.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.PropertyName
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
    var id: String = "",
    var userId: String = "",
    var authorId: String = "",
    var username: String = "", // Primary field for UI
    var authorAnonymousName: String = "", // Synced field for Firestore consistency
    var avatarColor: String = "#FFD700",
    var audioUrl: String = "",
    var createdAt: Timestamp = Timestamp.now(),
    @get:PropertyName("isPublic")
    @set:PropertyName("isPublic")
    var isPublic: Boolean = true, // Explicitly mapped for Firestore
    var visibility: Visibility = Visibility.PUBLIC,
    var status: ArtifactStatus = ArtifactStatus.DRAFT,
    @get:PropertyName("isDraft")
    @set:PropertyName("isDraft")
    var isDraft: Boolean = false, // Explicitly mapped for Firestore
    var duration: Long = 0,
    var title: String = "",
    var description: String = "",
    var reactions: Map<String, Int> = emptyMap(),
    var emotion: String = "",
    var emotionTag: String = "",
    var emotionConfidence: Float = 0f,
    var prompt: String = "",
    var playCount: Int = 0,
    var reactionCount: Int = 0,
    var avatarSeed: String = "",
    /** Snapshot of the author's identity at the time of publishing */
    var authorAvatarConfig: AvatarConfig = AvatarConfig(),
    /** @deprecated Use authorAvatarConfig.seed or avatarSeed */
    var avatarConfig: String? = null,
    /** @deprecated Use avatarSeed */
    var avatarConfigJson: String? = null,
    var commentCount: Int = 0,
    var moderationStatus: String = "CLEAN",
    var toxicityScore: Float = 0f,
    var reportCount: Int = 0,
    var safetyConcernCount: Int = 0,
    var reporterIds: List<String> = emptyList(),
    var transcript: List<TranscriptSegment> = emptyList(),
    var amplitudeData: List<Float> = emptyList(),
    var flaggedSegments: List<FlaggedSegment> = emptyList(),
    var moderation: ModerationMetadata = ModerationMetadata(),
    var conversationMetadata: ArtifactConversationMetadata = ArtifactConversationMetadata(),
    var reactionVisibility: ReactionVisibilityMode = ReactionVisibilityMode.APPROXIMATE
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
    var id: String = "",
    var text: String = "",
    var startMs: Long = 0,
    var endMs: Long = 0,
    var confidence: Float = 0f,
    var words: List<WordToken> = emptyList()
) {
    /**
     * Removes per-word timing to save memory when only the segment text is needed.
     */
    fun slim(): TranscriptSegment = this.copy(words = emptyList())
}

@Immutable
@Serializable
data class WordToken(
    var word: String = "",
    var startMs: Long = 0,
    var endMs: Long = 0
)

@Immutable
@Serializable
data class FlaggedSegment(
    var id: String = "",
    var type: PiiType = PiiType.OTHER, // e.g. "PHONE"
    var startMs: Long = 0,
    var endMs: Long = 0,
    var originalText: String = "",
    var confidence: Float = 0f,
    var isRedacted: Boolean = true,
    var userDecision: String = "PENDING" // "REDACT", "KEEP", "PENDING"
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
    val comments: List<ArtifactComment> = emptyList(),
    val reactionCounts: ArtifactReactionCounts? = null
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
