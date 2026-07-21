package com.saurabh.artifact.data.mapper

import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.model.Visibility
import com.saurabh.artifact.util.SecureString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftToArtifactMapperTest {

    private val mapper = DraftToArtifactMapper()

    @Test
    fun `map should preserve core draft fields`() {
        val draft = ArtifactDraftEntity(
            id = "draft_123",
            localAudioPath = "/path/to/audio.wav",
            title = "Sample Title",
            durationMs = 5000L,
            createdAt = 1626870000000L,
            amplitudeData = listOf(0.1f, 0.2f, 0.3f),
            isPublic = false
        )
        val author = AuthorSnapshot(anonymousId = "user_456", name = "Test User")

        val result = mapper.map(draft, author, "Fallback")

        assertEquals("draft_123", result.id)
        assertEquals("user_456", result.userId)
        assertEquals("Sample Title", result.title)
        assertEquals(5000L, result.durationMs)
        assertEquals(ArtifactStatus.DRAFT, result.status)
        assertEquals(listOf(0.1f, 0.2f, 0.3f), result.amplitudeData)
        assertTrue(result.isDraftField)
        assertEquals(Visibility.PRIVATE, result.visibility)
        assertEquals(author, result.author)
    }

    @Test
    fun `map should use fallback title when draft title is null`() {
        val draft = ArtifactDraftEntity(
            id = "draft_123",
            localAudioPath = "/path/to/audio.wav",
            title = null
        )
        val author = AuthorSnapshot(anonymousId = "user_456")

        val result = mapper.map(draft, author, "Fallback Title")

        assertEquals("Fallback Title", result.title)
    }

    @Test
    fun `map should normalize audio URL with file prefix`() {
        val draft1 = ArtifactDraftEntity(
            id = "d1",
            localAudioPath = "/absolute/path/audio.wav"
        )
        val draft2 = ArtifactDraftEntity(
            id = "d2",
            localAudioPath = "file:///absolute/path/audio.wav"
        )
        val author = AuthorSnapshot(anonymousId = "u1")

        val result1 = mapper.map(draft1, author, "T")
        val result2 = mapper.map(draft2, author, "T")

        assertEquals("file:///absolute/path/audio.wav", result1.audioUrl)
        assertEquals("file:///absolute/path/audio.wav", result2.audioUrl)
    }

    @Test
    fun `map should decode transcript segments when present`() {
        val transcriptJson = """[{"id":"s1","text":"Hello","startMs":0,"endMs":1000}]"""
        val draft = ArtifactDraftEntity(
            id = "draft_123",
            localAudioPath = "/path/to/audio.wav",
            transcriptSegmentsJson = SecureString.fromString(transcriptJson)
        )
        val author = AuthorSnapshot(anonymousId = "user_456")

        val result = mapper.map(draft, author, "T")

        assertEquals(1, result.transcript.size)
        assertEquals("s1", result.transcript[0].id)
        assertEquals("Hello", result.transcript[0].text)
        assertEquals(0L, result.transcript[0].startMs)
        assertEquals(1000L, result.transcript[0].endMs)
    }

    @Test
    fun `map should return empty transcript on malformed JSON`() {
        val draft = ArtifactDraftEntity(
            id = "draft_123",
            localAudioPath = "/path/to/audio.wav",
            transcriptSegmentsJson = SecureString.fromString("invalid-json")
        )
        val author = AuthorSnapshot(anonymousId = "user_456")

        val result = mapper.map(draft, author, "T")

        assertTrue(result.transcript.isEmpty())
    }

    @Test
    fun `map should cache transcript decoding`() {
        var decodeCount = 0
        val decoder = object : DraftToArtifactMapper.TranscriptDecoder {
            override fun decode(json: String): List<TranscriptSegment> {
                decodeCount++
                return emptyList()
            }
        }
        mapper.decoder = decoder

        val transcriptJson = """[]"""
        val draft = ArtifactDraftEntity(
            id = "d1",
            localAudioPath = "/path.wav",
            transcriptSegmentsJson = SecureString.fromString(transcriptJson)
        )
        val author = AuthorSnapshot(anonymousId = "u1")

        // First call
        mapper.map(draft, author, "T")
        assertEquals(1, decodeCount)

        // Second call with same transcript content (different object from Room simulation)
        val draftSameContent = draft.copy(
            transcriptSegmentsJson = SecureString.fromString(transcriptJson),
            reviewProgress = 0.5f // Metadata change
        )
        mapper.map(draftSameContent, author, "T")
        assertEquals(1, decodeCount) // Should be cached

        // Third call with different content
        val draftDifferentContent = draft.copy(
            transcriptSegmentsJson = SecureString.fromString("""[{"id":"s1"}]""")
        )
        mapper.map(draftDifferentContent, author, "T")
        assertEquals(2, decodeCount)
    }

    @Test
    fun `map should reuse Artifact instance when content fields are identical`() {
        val draft = ArtifactDraftEntity(id = "d1", localAudioPath = "/path.wav")
        val author = AuthorSnapshot(anonymousId = "u1")

        val result1 = mapper.map(draft, author, "T")
        
        // Simulate high-frequency metadata update (reviewProgress)
        val draftMetadataUpdate = draft.copy(reviewProgress = 0.8f, updatedAt = System.currentTimeMillis())
        val result2 = mapper.map(draftMetadataUpdate, author, "T")

        assertSame("Artifact instance should be reused for metadata-only updates", result1, result2)

        // Change a content field
        val draftContentUpdate = draft.copy(title = "New Title")
        val result3 = mapper.map(draftContentUpdate, author, "T")

        assertNotSame("Artifact instance should NOT be reused when content changes", result1, result3)
    }

    @Test
    fun `map should map lifecycle and isPublic to status and visibility`() {
        val author = AuthorSnapshot(anonymousId = "u1")
        
        // 1. Published draft
        val publishedDraft = ArtifactDraftEntity(
            id = "d1", 
            localAudioPath = "/p.wav",
            lifecycle = ArtifactLifecycle.PUBLISHED,
            isPublic = true
        )
        val result1 = mapper.map(publishedDraft, author, "T")
        assertEquals(ArtifactStatus.ACTIVE, result1.status)
        assertEquals(Visibility.PUBLIC, result1.visibility)
        assertTrue(result1.isPublic)

        // 2. Deleted draft
        val deletedDraft = ArtifactDraftEntity(
            id = "d2",
            localAudioPath = "/p.wav",
            lifecycle = ArtifactLifecycle.DELETED,
            isPublic = false
        )
        val result2 = mapper.map(deletedDraft, author, "T")
        assertEquals(ArtifactStatus.DELETED, result2.status)
        assertEquals(Visibility.PRIVATE, result2.visibility)
        assertTrue(!result2.isPublic)
    }
}
