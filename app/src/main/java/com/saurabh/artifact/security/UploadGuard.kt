package com.saurabh.artifact.security

import android.content.Context
import android.annotation.SuppressLint
import android.provider.Settings
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.util.FileIntegrity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadGuard @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        /**
         * Canonical metadata serialization and digest computation.
         * Formats title (trimmed) and emotions (uppercase ROOT, ordered, comma-separated)
         * to produce an identical digest on client and backend while preserving primary emotion.
         */
        fun computeMetadataDigest(title: String?, emotions: List<String>): String {
            val normTitle = title?.trim() ?: ""
            val normEmotions = emotions
                .map { it.trim().uppercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
                .joinToString(",")
            val raw = "title=$normTitle;emotions=$normEmotions"
            return FileIntegrity.hashString(raw)
        }

        /**
         * Canonical transcript digest computation from segments.
         */
        fun computeTranscriptDigest(transcript: List<TranscriptSegment>?): String {
            if (transcript.isNullOrEmpty()) return ""
            val canonical = transcript.joinToString("\n") { segment ->
                "${segment.startMs}:${segment.endMs}:${segment.text.trim()}"
            }
            return FileIntegrity.hashString(canonical)
        }

        /**
         * Canonical transcript digest computation from JSON string.
         */
        fun computeTranscriptDigest(json: String?): String {
            if (json.isNullOrBlank()) return ""
            return try {
                val segments = Json.decodeFromString<List<TranscriptSegment>>(json)
                computeTranscriptDigest(segments)
            } catch (e: Exception) {
                FileIntegrity.hashString(json.trim())
            }
        }
    }

    fun computeMetadataDigest(title: String?, emotions: List<String>): String =
        Companion.computeMetadataDigest(title, emotions)

    fun computeTranscriptDigest(transcript: List<TranscriptSegment>?): String =
        Companion.computeTranscriptDigest(transcript)

    fun computeTranscriptDigest(json: String?): String =
        Companion.computeTranscriptDigest(json)

    /**
     * Generates an integrity-bound verification token for the approval action.
     * Binds userId, draftId, approved metadata digest, approved transcript digest,
     * plaintext audio checksum, timestamp, and device fingerprint.
     */
    fun generateApprovalToken(
        userId: String,
        draftId: String,
        title: String?,
        emotions: List<String>,
        transcriptDigest: String,
        audioChecksum: String,
        timestamp: Long,
        deviceId: String = getDeviceFingerprint()
    ): String {
        val metadataDigest = computeMetadataDigest(title, emotions)
        val raw = "$userId:$draftId:$metadataDigest:$transcriptDigest:$audioChecksum:$timestamp:$deviceId"
        return FileIntegrity.hashString(raw)
    }

    /**
     * Backward-compatible overload for callers supplying only audio checksum.
     */
    fun generateApprovalToken(
        userId: String,
        draftId: String,
        checksum: String,
        timestamp: Long
    ): String {
        return generateApprovalToken(
            userId = userId,
            draftId = draftId,
            title = null,
            emotions = emptyList(),
            transcriptDigest = "",
            audioChecksum = checksum,
            timestamp = timestamp
        )
    }

    /**
     * Calculates the canonical plaintext audio checksum of a file.
     * Decrypts stream on-the-fly if encrypted, or calculates stream checksum directly if unencrypted.
     */
    fun calculatePlaintextChecksum(filePath: String, isEncrypted: Boolean): String {
        val file = File(filePath)
        if (!file.exists()) return ""
        return try {
            val inputStream = if (isEncrypted) {
                SecurityArchitecture.openDecryptingStream(context, file)
            } else {
                file.inputStream()
            }
            FileIntegrity.calculateStreamChecksum(inputStream)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Validates that the draft is in a state allowed for upload and the token is correct.
     * Performs strict integrity checks on the complete approved snapshot:
     * title, emotions, transcript digest, frozen audio file, approval timestamp, and device.
     */
    fun validateApproval(draft: ArtifactDraftEntity, userId: String): Boolean {
        // 1. State check
        if (draft.lifecycle != ArtifactLifecycle.READY_TO_PUBLISH && draft.lifecycle != ArtifactLifecycle.PUBLISHED) {
            return false
        }

        // 2. Integrity check: Independently calculate the plaintext audio checksum of the ACTUAL frozen file
        val audioPath = draft.frozenAudioPath ?: draft.localAudioPath
        val currentChecksum = calculatePlaintextChecksum(audioPath, draft.isEncrypted)

        if (currentChecksum.isEmpty()) return false

        if (draft.checksum != null && draft.checksum != currentChecksum) {
            return false
        }

        // 3. Extract approved metadata and transcript digests from draft snapshot
        val draftEmotions = if (draft.emotions.isNotEmpty()) {
            draft.emotions.map { it.name }
        } else if (draft.emotion != null) {
            listOf(draft.emotion.name)
        } else {
            emptyList()
        }
        val transcriptDigest = computeTranscriptDigest(draft.frozenTranscriptJson?.toUnsecureString())

        // 4. Token check: Verify the approval token matches the current snapshot state
        val expectedToken = generateApprovalToken(
            userId = userId,
            draftId = draft.id,
            title = draft.title,
            emotions = draftEmotions,
            transcriptDigest = transcriptDigest,
            audioChecksum = currentChecksum,
            timestamp = draft.publishApprovalTimestamp ?: 0L,
            deviceId = draft.deviceFingerprint ?: getDeviceFingerprint()
        )

        if (draft.approvalToken == null || draft.approvalToken != expectedToken) return false

        // 5. Device check
        if (draft.deviceFingerprint != getDeviceFingerprint()) return false

        return true
    }

    @SuppressLint("HardwareIds")
    fun getDeviceFingerprint(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
    }
}
