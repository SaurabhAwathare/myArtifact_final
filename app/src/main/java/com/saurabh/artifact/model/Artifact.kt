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
    DRAFT,
    PENDING_UPLOAD,
    ACTIVE,
    HIDDEN
}

@Immutable
data class Artifact(
    var id: String = "",
    @get:Exclude var userId: String = "", // Internal UID - EXCLUDED FROM FIRESTORE PUBLIC
    var author: AuthorSnapshot = AuthorSnapshot(),
    var audioUrl: String = "",
    var createdAt: Timestamp = Timestamp.now(),
    @get:PropertyName("isPublic")
    @set:PropertyName("isPublic")
    var isPublic: Boolean = true,
    var visibility: Visibility = Visibility.PUBLIC,
    var status: ArtifactStatus = ArtifactStatus.DRAFT,
    @get:PropertyName("isDraft")
    @set:PropertyName("isDraft")
    var isDraft: Boolean = false,
    var durationMs: Long = 0,
    var checksum: String = "", // Added for tamper resistance and deduplication
    var title: String = "",
    var description: String = "",
    var reactions: Map<String, Int> = emptyMap(),
    var emotion: String = "",
    var emotionTag: String = "",
    var emotionConfidence: Float = 0f,
    var prompt: String = "",
    var playCount: Int = 0,
    var reactionCount: Int = 0,
    var commentCount: Int = 0,
    var moderationStatus: String = "CLEAN",
    var toxicityScore: Float = 0f,
    var reportCount: Int = 0,
    var safetyConcernCount: Int = 0,
    var reporterIds: List<String> = emptyList(),
    @get:Exclude var transcript: List<TranscriptSegment> = emptyList(),
    var transcriptUrl: String? = null,
    var amplitudeData: List<Float> = emptyList(),
    var flaggedSegments: List<FlaggedSegment> = emptyList(),
    var moderation: ModerationMetadata = ModerationMetadata(),
    var conversationMetadata: ArtifactConversationMetadata = ArtifactConversationMetadata(),
    var reactionVisibility: ReactionVisibilityMode = ReactionVisibilityMode.APPROXIMATE,

    // Missing fields causing warnings
    var authorAnonymousName: String = "",
    var authorId: String = "",
    var username: String = ""
) {
    @get:Exclude
    val authorAvatarConfig: AvatarConfig
        get() = author.avatarConfig
            .copy(seed = author.avatarConfig.seed.ifEmpty { author.avatarSeed.ifEmpty { userId } })

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

enum class NotificationType {
    RESONANCE,    // General reaction
    REFLECTION,   // Comment/Reply
    SUPPORT,      // Strength/Space
    PRESENCE,     // Witnessed/Viewed (future)
    SYSTEM        // Upload/Admin
}

data class NotificationItem(
    val id: String = "",
    val userId: String = "",
    val message: String = "Someone resonated with your artifact 💬",
    val artifactId: String = "",
    val type: NotificationType = NotificationType.RESONANCE,
    val createdAt: Timestamp = Timestamp.now(),
    val isRead: Boolean = false
)
