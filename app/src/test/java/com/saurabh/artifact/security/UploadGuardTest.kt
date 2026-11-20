package com.saurabh.artifact.security

import android.content.Context
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.util.SecureString
import io.mockk.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UploadGuardTest {

    private val mockContext = mockk<Context>(relaxed = true)
    private lateinit var uploadGuard: UploadGuard

    private val testUserId = "user123"
    private val testDraftId = "draft456"
    private val testChecksum = "abc123checksum"
    private val testFingerprint = "test_device_id"
    private val testTimestamp = 1623250000000L

    @Before
    fun setup() {
        uploadGuard = spyk(UploadGuard(mockContext))
        every { uploadGuard.getDeviceFingerprint() } returns testFingerprint
    }

    @Test
    fun `computeMetadataDigest produces canonical consistent digest for whitespace and case but preserves emotion order`() {
        val digest1 = uploadGuard.computeMetadataDigest(" My Title ", listOf("Calm", "Grateful"))
        val digestSameOrder = uploadGuard.computeMetadataDigest("My Title", listOf("CALM", "grateful"))
        val digestReordered = uploadGuard.computeMetadataDigest("My Title", listOf("GRATEFUL", "calm"))
        val digestDifferent = uploadGuard.computeMetadataDigest("My Title", listOf("Calm", "Happy"))

        assertEquals(digest1, digestSameOrder)
        assertNotEquals(digest1, digestReordered)
        assertNotEquals(digest1, digestDifferent)
    }

    @Test
    fun `computeMetadataDigest is invariant across English Turkish and Azerbaijani locales`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            val englishDigest = uploadGuard.computeMetadataDigest("Title", listOf("Insightful", "Inspiring"))

            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val turkishDigest = uploadGuard.computeMetadataDigest("Title", listOf("Insightful", "Inspiring"))

            Locale.setDefault(Locale.forLanguageTag("az-AZ"))
            val azerbaijaniDigest = uploadGuard.computeMetadataDigest("Title", listOf("Insightful", "Inspiring"))

            assertEquals("Turkish locale must produce identical digest to English", englishDigest, turkishDigest)
            assertEquals("Azerbaijani locale must produce identical digest to English", englishDigest, azerbaijaniDigest)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `computeMetadataDigest handles unicode whitespace normalization and empty values safely`() {
        val d1 = uploadGuard.computeMetadataDigest("  Unicode Title \u00A0", listOf(" Calm ", "  ", "Grateful"))
        val d2 = uploadGuard.computeMetadataDigest("Unicode Title", listOf("CALM", "GRATEFUL"))
        assertEquals(d1, d2)

        val dEmpty = uploadGuard.computeMetadataDigest(null, emptyList())
        assertNotNull(dEmpty)
        assertTrue(dEmpty.isNotEmpty())
    }

    @Test
    fun `computeTranscriptDigest produces canonical consistent digest`() {
        val segments1 = listOf(
            TranscriptSegment(text = "Hello world", startMs = 0, endMs = 1000),
            TranscriptSegment(text = "Second sentence", startMs = 1001, endMs = 2000)
        )
        val json1 = """[{"text":"Hello world","startMs":0,"endMs":1000},{"text":"Second sentence","startMs":1001,"endMs":2000}]"""

        val digestFromObjects = uploadGuard.computeTranscriptDigest(segments1)
        val digestFromJson = uploadGuard.computeTranscriptDigest(json1)

        assertEquals(digestFromObjects, digestFromJson)
    }

    @Test
    fun `generateApprovalToken produces consistent deterministic token`() {
        val token1 = uploadGuard.generateApprovalToken(
            userId = testUserId,
            draftId = testDraftId,
            title = "Title",
            emotions = listOf("CALM"),
            transcriptDigest = "transcript_digest_123",
            audioChecksum = testChecksum,
            timestamp = testTimestamp,
            deviceId = testFingerprint
        )
        val token2 = uploadGuard.generateApprovalToken(
            userId = testUserId,
            draftId = testDraftId,
            title = "Title",
            emotions = listOf("CALM"),
            transcriptDigest = "transcript_digest_123",
            audioChecksum = testChecksum,
            timestamp = testTimestamp,
            deviceId = testFingerprint
        )

        assertNotNull(token1)
        assertTrue(token1.isNotEmpty())
        assertEquals(token1, token2)
    }

    @Test
    fun `validateApproval returns true for unmodified approved content`() {
        val audioPath = "dummy/path/audio.wav"
        val title = "Approved Title"
        val emotions = listOf(Emotion.CALM, Emotion.GRATEFUL)
        val transcriptJson = """[{"text":"Approved text","startMs":0,"endMs":1000}]"""
        val transcriptDigest = uploadGuard.computeTranscriptDigest(transcriptJson)

        val token = uploadGuard.generateApprovalToken(
            userId = testUserId,
            draftId = testDraftId,
            title = title,
            emotions = emotions.map { it.name },
            transcriptDigest = transcriptDigest,
            audioChecksum = testChecksum,
            timestamp = testTimestamp,
            deviceId = testFingerprint
        )

        val draft = ArtifactDraftEntity(
            id = testDraftId,
            userId = testUserId,
            title = title,
            emotions = emotions,
            frozenTranscriptJson = SecureString.fromString(transcriptJson),
            localAudioPath = audioPath,
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            checksum = testChecksum,
            approvalToken = token,
            deviceFingerprint = testFingerprint,
            publishApprovalTimestamp = testTimestamp
        )

        every { uploadGuard.calculatePlaintextChecksum(audioPath, false) } returns testChecksum

        val result = uploadGuard.validateApproval(draft, testUserId)
        assertTrue(result)
    }

    @Test
    fun `validateApproval returns false when title is modified after approval`() {
        val audioPath = "dummy/path/audio.wav"
        val title = "Approved Title"
        val emotions = listOf(Emotion.CALM)
        val transcriptJson = """[{"text":"Approved text","startMs":0,"endMs":1000}]"""
        val transcriptDigest = uploadGuard.computeTranscriptDigest(transcriptJson)

        val token = uploadGuard.generateApprovalToken(
            userId = testUserId,
            draftId = testDraftId,
            title = title,
            emotions = emotions.map { it.name },
            transcriptDigest = transcriptDigest,
            audioChecksum = testChecksum,
            timestamp = testTimestamp,
            deviceId = testFingerprint
        )

        // Draft with modified title
        val draft = ArtifactDraftEntity(
            id = testDraftId,
            userId = testUserId,
            title = "Tampered / Modified Title",
            emotions = emotions,
            frozenTranscriptJson = SecureString.fromString(transcriptJson),
            localAudioPath = audioPath,
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            checksum = testChecksum,
            approvalToken = token,
            deviceFingerprint = testFingerprint,
            publishApprovalTimestamp = testTimestamp
        )

        every { uploadGuard.calculatePlaintextChecksum(audioPath, false) } returns testChecksum

        val result = uploadGuard.validateApproval(draft, testUserId)
        assertFalse("Title modification must invalidate approval", result)
    }

    @Test
    fun `validateApproval returns false when emotions are modified after approval`() {
        val audioPath = "dummy/path/audio.wav"
        val title = "Approved Title"
        val emotions = listOf(Emotion.CALM)
        val transcriptJson = """[{"text":"Approved text","startMs":0,"endMs":1000}]"""
        val transcriptDigest = uploadGuard.computeTranscriptDigest(transcriptJson)

        val token = uploadGuard.generateApprovalToken(
            userId = testUserId,
            draftId = testDraftId,
            title = title,
            emotions = emotions.map { it.name },
            transcriptDigest = transcriptDigest,
            audioChecksum = testChecksum,
            timestamp = testTimestamp,
            deviceId = testFingerprint
        )

        // Draft with modified emotions
        val draft = ArtifactDraftEntity(
            id = testDraftId,
            userId = testUserId,
            title = title,
            emotions = listOf(Emotion.ANGRY),
            frozenTranscriptJson = SecureString.fromString(transcriptJson),
            localAudioPath = audioPath,
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            checksum = testChecksum,
            approvalToken = token,
            deviceFingerprint = testFingerprint,
            publishApprovalTimestamp = testTimestamp
        )

        every { uploadGuard.calculatePlaintextChecksum(audioPath, false) } returns testChecksum

        val result = uploadGuard.validateApproval(draft, testUserId)
        assertFalse("Emotion modification must invalidate approval", result)
    }

    @Test
    fun `validateApproval returns false when transcript is modified after approval`() {
        val audioPath = "dummy/path/audio.wav"
        val title = "Approved Title"
        val emotions = listOf(Emotion.CALM)
        val transcriptJson = """[{"text":"Approved text","startMs":0,"endMs":1000}]"""
        val transcriptDigest = uploadGuard.computeTranscriptDigest(transcriptJson)

        val token = uploadGuard.generateApprovalToken(
            userId = testUserId,
            draftId = testDraftId,
            title = title,
            emotions = emotions.map { it.name },
            transcriptDigest = transcriptDigest,
            audioChecksum = testChecksum,
            timestamp = testTimestamp,
            deviceId = testFingerprint
        )

        // Draft with modified transcript JSON
        val draft = ArtifactDraftEntity(
            id = testDraftId,
            userId = testUserId,
            title = title,
            emotions = emotions,
            frozenTranscriptJson = SecureString.fromString("""[{"text":"Tampered transcript text","startMs":0,"endMs":1000}]"""),
            localAudioPath = audioPath,
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            checksum = testChecksum,
            approvalToken = token,
            deviceFingerprint = testFingerprint,
            publishApprovalTimestamp = testTimestamp
        )

        every { uploadGuard.calculatePlaintextChecksum(audioPath, false) } returns testChecksum

        val result = uploadGuard.validateApproval(draft, testUserId)
        assertFalse("Transcript modification must invalidate approval", result)
    }

    @Test
    fun `validateApproval returns false when audio checksum is modified`() {
        val audioPath = "dummy/path/audio.wav"
        val title = "Approved Title"
        val emotions = listOf(Emotion.CALM)
        val transcriptJson = """[{"text":"Approved text","startMs":0,"endMs":1000}]"""
        val transcriptDigest = uploadGuard.computeTranscriptDigest(transcriptJson)

        val token = uploadGuard.generateApprovalToken(
            userId = testUserId,
            draftId = testDraftId,
            title = title,
            emotions = emotions.map { it.name },
            transcriptDigest = transcriptDigest,
            audioChecksum = testChecksum,
            timestamp = testTimestamp,
            deviceId = testFingerprint
        )

        val draft = ArtifactDraftEntity(
            id = testDraftId,
            userId = testUserId,
            title = title,
            emotions = emotions,
            frozenTranscriptJson = SecureString.fromString(transcriptJson),
            localAudioPath = audioPath,
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            checksum = testChecksum,
            approvalToken = token,
            deviceFingerprint = testFingerprint,
            publishApprovalTimestamp = testTimestamp
        )

        // Actual file checksum differs from approved checksum
        every { uploadGuard.calculatePlaintextChecksum(audioPath, false) } returns "MODIFIED_AUDIO_CHECKSUM"

        val result = uploadGuard.validateApproval(draft, testUserId)
        assertFalse("Audio checksum mismatch must invalidate approval", result)
    }

    @Test
    fun `validateApproval returns false when device fingerprint mismatch`() {
        val audioPath = "dummy/path/audio.wav"
        val title = "Approved Title"
        val emotions = listOf(Emotion.CALM)
        val transcriptJson = """[{"text":"Approved text","startMs":0,"endMs":1000}]"""
        val transcriptDigest = uploadGuard.computeTranscriptDigest(transcriptJson)

        val token = uploadGuard.generateApprovalToken(
            userId = testUserId,
            draftId = testDraftId,
            title = title,
            emotions = emotions.map { it.name },
            transcriptDigest = transcriptDigest,
            audioChecksum = testChecksum,
            timestamp = testTimestamp,
            deviceId = testFingerprint
        )

        val draft = ArtifactDraftEntity(
            id = testDraftId,
            userId = testUserId,
            title = title,
            emotions = emotions,
            frozenTranscriptJson = SecureString.fromString(transcriptJson),
            localAudioPath = audioPath,
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            checksum = testChecksum,
            approvalToken = token,
            deviceFingerprint = "DIFFERENT_DEVICE_FINGERPRINT",
            publishApprovalTimestamp = testTimestamp
        )

        every { uploadGuard.calculatePlaintextChecksum(audioPath, false) } returns testChecksum

        val result = uploadGuard.validateApproval(draft, testUserId)
        assertFalse("Device fingerprint mismatch must invalidate approval", result)
    }

    @Test
    fun `validateApproval returns false when userId mismatch`() {
        val audioPath = "dummy/path/audio.wav"
        val title = "Approved Title"
        val emotions = listOf(Emotion.CALM)
        val transcriptJson = """[{"text":"Approved text","startMs":0,"endMs":1000}]"""
        val transcriptDigest = uploadGuard.computeTranscriptDigest(transcriptJson)

        val token = uploadGuard.generateApprovalToken(
            userId = testUserId,
            draftId = testDraftId,
            title = title,
            emotions = emotions.map { it.name },
            transcriptDigest = transcriptDigest,
            audioChecksum = testChecksum,
            timestamp = testTimestamp,
            deviceId = testFingerprint
        )

        val draft = ArtifactDraftEntity(
            id = testDraftId,
            userId = testUserId,
            title = title,
            emotions = emotions,
            frozenTranscriptJson = SecureString.fromString(transcriptJson),
            localAudioPath = audioPath,
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            checksum = testChecksum,
            approvalToken = token,
            deviceFingerprint = testFingerprint,
            publishApprovalTimestamp = testTimestamp
        )

        every { uploadGuard.calculatePlaintextChecksum(audioPath, false) } returns testChecksum

        val result = uploadGuard.validateApproval(draft, "different_user_456")
        assertFalse("User mismatch must invalidate approval", result)
    }
}
