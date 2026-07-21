package com.saurabh.artifact.data.mapper

import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.Visibility
import org.junit.Assert.assertEquals
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
            amplitudeData = listOf(0.1f, 0.2f, 0.3f)
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
}
