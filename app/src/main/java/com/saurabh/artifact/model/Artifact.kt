package com.saurabh.artifact.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties
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
    HIDDEN,
    DELETED
}

@Immutable
@IgnoreExtraProperties
data class Artifact(
    val id: String = "",
    val userId: String = "", // Internal UID - Required for security rules ownership check
    val author: AuthorSnapshot = AuthorSnapshot(),
    val audioUrl: String = "",
    val createdAt: Timestamp = Timestamp.now(),
    @get:PropertyName("isPublic")
    val isPublic: Boolean = true,
    val visibility: Visibility = Visibility.PUBLIC,
    val status: ArtifactStatus = ArtifactStatus.DRAFT,
    val durationMs: Long = 0,
    val checksum: String = "", // Added for tamper resistance and deduplication
    val isEncrypted: Boolean = false, // Added for playback signaling
    val title: String = "",
    val description: String = "",
    val reactions: Map<String, Long> = emptyMap(),
    val emotion: String = "",
    val emotionTag: String = "",
    val emotionConfidence: Float = 0f,
    val prompt: String = "",
    val playCount: Long = 0,
    val reactionCount: Long = 0,
    val moderationStatus: String = "CLEAN",
    val toxicityScore: Float = 0f,
    val reportCount: Long = 0,
    val safetyConcernCount: Long = 0,
    val reporterIds: List<String> = emptyList(),
    @get:Exclude val transcript: List<TranscriptSegment> = emptyList(),
    val transcriptUrl: String? = null,
    val amplitudeData: List<Float> = emptyList(),
    val flaggedSegments: List<FlaggedSegment> = emptyList(),
    val moderation: ModerationMetadata = ModerationMetadata(),
    val recommendationState: RecommendationState = RecommendationState.ACTIVE,
    val conversationMetadata: ArtifactConversationMetadata = ArtifactConversationMetadata(),
    val reactionVisibility: ReactionVisibilityMode = ReactionVisibilityMode.APPROXIMATE,
    val titleHistory: List<String> = emptyList(),

    /**
     * Firestore Release compatibility.
     *
     * The Firestore document field is named "isDraft", while the Kotlin property
     * is named "isDraftField" to avoid colliding with the computed helper
     * property `isDraft`.
     *
     * Both @get:PropertyName and @field:PropertyName are intentionally required
     * for immutable constructor deserialization in Release builds. @field is used
     * because @param is not supported by this annotation.
     *
     * See RELEASE-B04.
     */
    @get:PropertyName("isDraft")
    @field:PropertyName("isDraft")
    val isDraftField: Boolean = false,

    // Missing fields causing warnings
    val commentCount: Long = 0L,
    val lastReportedAt: Timestamp? = null,
    val authorAnonymousName: String = "",
    val authorId: String = "",
    val username: String = "",
) {
    /**
     * Helper to check if the artifact is in a draft state.
     */
    @get:Exclude
    val isDraft: Boolean
        get() = (status == ArtifactStatus.DRAFT) || (status == ArtifactStatus.PENDING_UPLOAD)

    @get:Exclude
    val authorSigilConfig: SigilConfig
        get() = author.sigilConfig
            .copy(seed = author.sigilConfig.seed.ifEmpty { author.sigilSeed.ifEmpty { userId } })

    /**
     * Returns a lightweight version of the artifact suitable for large lists (feed).
     * Strips heavy lists like transcript and original amplitude data to stay under Binder limits.
     */
    fun slimForFeed(): Artifact {
        return this.copy(
            transcript = emptyList(),
            amplitudeData = if (amplitudeData.size > 64) amplitudeData.take(64) else amplitudeData,
            flaggedSegments = emptyList(),
            reporterIds = emptyList(),
        )
    }
}

/**
 * Optimized binary search for finding a transcript segment at a specific time.
 * Complexity: O(log N) where N is the number of segments.
 */
fun List<TranscriptSegment>.findSegmentAt(positionMs: Long): TranscriptSegment? {
    if (isEmpty()) return null
    
    var low = 0
    var high = size - 1
    
    while (low <= high) {
        val mid = (low + high) ushr 1
        val midVal = get(mid)
        
        when {
            positionMs < midVal.startMs -> high = mid - 1
            positionMs > midVal.endMs -> low = mid + 1
            else -> return midVal // Found it
        }
    }
    return null
}

@Immutable
@Serializable
data class TranscriptSegment(
    val id: String = "",
    val text: String = "",
    val startMs: Long = 0,
    val endMs: Long = 0,
    val confidence: Float = 0f,
    val words: List<WordToken> = emptyList(),
) {
    /**
     * Removes per-word timing to save memory when only the segment text is needed.
     */
    @Suppress("unused")
    fun slim(): TranscriptSegment = this.copy(words = emptyList())
}

@Immutable
@Serializable
data class WordToken(
    val word: String = "",
    val startMs: Long = 0,
    val endMs: Long = 0,
)

@Immutable
@Serializable
data class FlaggedSegment(
    val id: String = "",
    val type: PiiType = PiiType.OTHER, // e.g. "PHONE"
    val startMs: Long = 0,
    val endMs: Long = 0,
    val originalText: String = "",
    val confidence: Float = 0f,
    val isRedacted: Boolean = true,
    val userDecision: String = "PENDING", // "REDACT", "KEEP", "PENDING"
)

@Serializable
enum class PiiType {
    NAME, LOCATION, PHONE, EMAIL, @Suppress("unused") ID_NUMBER, OTHER
}

/**
 * Heavy data container for Artifacts, loaded only when needed (e.g., detail view or expansion).
 */
@Immutable
data class ArtifactDetail(
    val id: String = "",
    val amplitudeData: List<Float> = emptyList(),
    val reactionCounts: ArtifactReactionCounts? = null,
)

enum class NotificationType {
    RESONANCE,    // General reaction
    @Suppress("unused") SUPPORT,      // Strength/Space
    @Suppress("unused") PRESENCE,     // Witnessed/Viewed (future)
    @Suppress("unused") SYSTEM,       // Upload/Admin
    COMMENT,          // New comment on artifact
    FOLLOW            // Someone started following you
}

data class NotificationItem(
    var id: String = "",
    var userId: String = "",
    var followerId: String = "",
    var message: String = "Someone resonated with your artifact 💬",
    var artifactId: String = "",
    var type: NotificationType = NotificationType.RESONANCE,
    var createdAt: Timestamp = Timestamp.now(),
    @get:PropertyName("isRead")
    @set:PropertyName("isRead")
    var isRead: Boolean = false,
)
