package com.saurabh.artifact.data.mapper

import com.google.firebase.Timestamp
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.Visibility
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.util.SecureString
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized mapper to convert [ArtifactDraftEntity] into a domain [Artifact].
 * This ensures consistency between different parts of the app that need to display or play drafts.
 *
 * Optimization: Uses two-tier caching to avoid redundant transcript decoding and Artifact allocations
 * during high-frequency runtime updates (e.g. review progress changes).
 */
@Singleton
class DraftToArtifactMapper @Inject constructor() {

    private val instanceId = Integer.toHexString(System.identityHashCode(this))

    init {
        ArtifactLogger.d(
            DiagnosticCategory.DATABASE,
            "MAPPER_INSTANCE_CREATED",
            mapOf("instanceId" to instanceId)
        )
    }

    internal interface TranscriptDecoder {
        fun decode(json: String): List<TranscriptSegment>
    }

    private val defaultDecoder = object : TranscriptDecoder {
        override fun decode(json: String): List<TranscriptSegment> {
            return kotlinx.serialization.json.Json.decodeFromString(json)
        }
    }

    // Accessible for behavior-based testing
    internal var decoder: TranscriptDecoder = defaultDecoder

    private val transcriptCache = ConcurrentHashMap<TranscriptCacheKey, List<TranscriptSegment>>()
    private val artifactCache = ConcurrentHashMap<String, CachedArtifact>()

    private data class TranscriptCacheKey(val draftId: String, val contentHash: Int)
    private data class CachedArtifact(val artifact: Artifact, val signature: MappingSignature)
    private data class MappingSignature(
        val audioPath: String,
        val title: String,
        val durationMs: Long,
        val createdAt: Long,
        val amplitudeHash: Int,
        val author: AuthorSnapshot,
        val transcriptHash: Int?,
        val isPublic: Boolean,
        val isEncrypted: Boolean,
        val lifecycle: ArtifactLifecycle
    )

    /**
     * Maps a local draft entity to a displayable Artifact.
     */
    fun map(
        draft: ArtifactDraftEntity,
        author: AuthorSnapshot,
        fallbackTitle: String
    ): Artifact {
        val transcriptHash = draft.transcriptSegmentsJson?.contentHash()
        val transcript = getOrDecodeTranscript(draft, transcriptHash)

        val signature = MappingSignature(
            audioPath = draft.localAudioPath,
            title = draft.title ?: fallbackTitle,
            durationMs = draft.durationMs,
            createdAt = draft.createdAt,
            amplitudeHash = draft.amplitudeData.hashCode(),
            author = author,
            transcriptHash = transcriptHash,
            isPublic = draft.isPublic,
            isEncrypted = draft.isEncrypted,
            lifecycle = draft.lifecycle
        )

        val cached = artifactCache[draft.id]
        if (cached != null) {
            if (cached.signature == signature) {
                ArtifactLogger.d(
                    DiagnosticCategory.DATABASE,
                    "MAPPER_CACHE_HIT",
                    mapOf(
                        "instanceId" to instanceId,
                        LogKeys.DRAFT_ID to draft.id
                    )
                )
                return cached.artifact
            } else {
                ArtifactLogger.d(
                    DiagnosticCategory.DATABASE,
                    "MAPPER_CACHE_MISS",
                    mapOf(
                        "instanceId" to instanceId,
                        LogKeys.DRAFT_ID to draft.id,
                        "reason" to "SIGNATURE_MISMATCH",
                        "oldSignatureHash" to cached.signature.hashCode().toString(),
                        "newSignatureHash" to signature.hashCode().toString()
                    )
                )
            }
        } else {
            ArtifactLogger.d(
                DiagnosticCategory.DATABASE,
                "MAPPER_CACHE_MISS",
                mapOf(
                    "instanceId" to instanceId,
                    LogKeys.DRAFT_ID to draft.id,
                    "reason" to "MISSING"
                )
            )
        }

        val artifact = Artifact(
            id = draft.id,
            userId = draft.userId, // RESTORED CONTRACT: Use creator's internal UID
            author = author,
            audioUrl = normalizeAudioUrl(draft.localAudioPath),
            createdAt = Timestamp(Date(draft.createdAt)),
            title = draft.title ?: fallbackTitle,
            durationMs = draft.durationMs,
            status = when (draft.lifecycle) {
                ArtifactLifecycle.PUBLISHED -> ArtifactStatus.ACTIVE
                ArtifactLifecycle.DELETED,
                ArtifactLifecycle.DELETING -> ArtifactStatus.DELETED
                else -> ArtifactStatus.DRAFT
            },
            amplitudeData = draft.amplitudeData,
            transcript = transcript,
            isDraftField = true,
            isEncrypted = draft.isEncrypted,
            visibility = if (draft.isPublic) Visibility.PUBLIC else Visibility.PRIVATE,
            isPublic = draft.isPublic
        )

        artifactCache[draft.id] = CachedArtifact(artifact, signature)
        return artifact
    }

    private fun getOrDecodeTranscript(draft: ArtifactDraftEntity, contentHash: Int?): List<TranscriptSegment> {
        if (contentHash == null) return emptyList()

        val key = TranscriptCacheKey(draft.id, contentHash)
        return transcriptCache.getOrPut(key) {
            ArtifactLogger.d(DiagnosticCategory.DATABASE, "TRANSCRIPT_DECODE_STARTED", mapOf(LogKeys.DRAFT_ID to draft.id))
            runCatching {
                val decoded = decoder.decode(draft.transcriptSegmentsJson!!.toUnsecureString())
                ArtifactLogger.d(
                    DiagnosticCategory.DATABASE,
                    "TRANSCRIPT_DECODE_SUCCESS",
                    mapOf(
                        LogKeys.DRAFT_ID to draft.id,
                        "segmentCount" to decoded.size
                    )
                )
                decoded
            }.getOrElse { e ->
                ArtifactLogger.e(
                    DiagnosticCategory.DATABASE,
                    "TRANSCRIPT_DECODE_FAILED",
                    mapOf(
                        LogKeys.DRAFT_ID to draft.id,
                        LogKeys.EXCEPTION_CLASS to e.javaClass.simpleName
                    )
                )
                emptyList()
            }
        }
    }

    private fun SecureString.contentHash(): Int = toUnsecureString().hashCode()

    /**
     * Ensures the audio URL is a valid local file URI.
     */
    private fun normalizeAudioUrl(path: String): String {
        return if (path.startsWith("file://")) {
            path
        } else {
            "file://$path"
        }
    }

    /**
     * Clears caches. Should be called when drafts are deleted or the user logs out.
     */
    fun invalidateCache(draftId: String) {
        artifactCache.remove(draftId)
        // Note: Transcript cache uses content hash, so we might keep some orphan segments 
        // but it's keyed by draftId too so we can filter if needed.
        val keysToRemove = transcriptCache.keys().asSequence().filter { it.draftId == draftId }.toList()
        keysToRemove.forEach { transcriptCache.remove(it) }
    }
}
