package com.saurabh.artifact.ui.player

import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerArtifactMapperTest {

    @Test
    fun `toPlayerArtifact should map isPublic correctly`() {
        val publicArtifact = Artifact(id = "1", isPublic = true, status = ArtifactStatus.ACTIVE)
        val privateArtifact = Artifact(id = "2", isPublic = false, status = ArtifactStatus.ACTIVE)

        val publicPlayerArtifact = publicArtifact.toPlayerArtifact()
        val privatePlayerArtifact = privateArtifact.toPlayerArtifact()

        assertEquals(true, publicPlayerArtifact.isPublic)
        assertEquals(false, privatePlayerArtifact.isPublic)
    }

    @Test
    fun `toPlayerArtifact should map other fields correctly`() {
        val artifact = Artifact(
            id = "test-id",
            title = "Test Title",
            isPublic = true,
            status = ArtifactStatus.ACTIVE,
            episodeNumber = 42L
        )

        val playerArtifact = artifact.toPlayerArtifact()

        assertEquals("test-id", playerArtifact.id)
        assertEquals("Test Title", playerArtifact.title)
        assertEquals(true, playerArtifact.isPublic)
        assertEquals(42L, playerArtifact.episodeNumber)
    }

    @Test
    fun `toPlayerArtifact should preserve null episodeNumber`() {
        val artifact = Artifact(
            id = "test-id",
            title = "Test Title",
            episodeNumber = null,
            status = ArtifactStatus.ACTIVE
        )

        val playerArtifact = artifact.toPlayerArtifact()

        assertEquals(null, playerArtifact.episodeNumber)
    }

    @Test
    fun `toPlayerArtifact should map qualifiedPlayCount correctly`() {
        val artifact = Artifact(
            id = "test-qualified",
            title = "Qualified Play Test",
            qualifiedPlayCount = 42L,
            playCount = 1000L,
            status = ArtifactStatus.ACTIVE
        )

        val playerArtifact = artifact.toPlayerArtifact()

        assertEquals(42L, playerArtifact.qualifiedPlayCount)
    }

    @Test
    fun `toPlayerArtifact should keep historical playCount separate from qualifiedPlayCount`() {
        val artifact = Artifact(
            id = "test-separate",
            title = "Separation Test",
            playCount = 9999L,
            qualifiedPlayCount = 7L,
            status = ArtifactStatus.ACTIVE
        )

        val playerArtifact = artifact.toPlayerArtifact()

        assertEquals(7L, playerArtifact.qualifiedPlayCount)
        assertEquals(9999L, artifact.playCount)
    }

    @Test
    fun `toPlayerArtifact should default missing qualifiedPlayCount to 0`() {
        val artifact = Artifact(
            id = "test-default",
            title = "Default Test",
            status = ArtifactStatus.ACTIVE
        )

        val playerArtifact = artifact.toPlayerArtifact()

        assertEquals(0L, playerArtifact.qualifiedPlayCount)
    }

    @Test
    fun `toPlayerArtifact should handle large valid qualifiedPlayCount without overflow or truncation`() {
        val largeCount = 10_000_000_000L // 10 billion, exceeds 32-bit integer max
        val artifact = Artifact(
            id = "test-large",
            title = "Large Count Test",
            qualifiedPlayCount = largeCount,
            status = ArtifactStatus.ACTIVE
        )

        val playerArtifact = artifact.toPlayerArtifact()

        assertEquals(largeCount, playerArtifact.qualifiedPlayCount)
    }

    @Test
    fun `toPlayerArtifact should map commentCount correctly`() {
        val artifact = Artifact(
            id = "test-comment",
            title = "Comment Count Test",
            commentCount = 15L,
            status = ArtifactStatus.ACTIVE
        )

        val playerArtifact = artifact.toPlayerArtifact()

        assertEquals(15L, playerArtifact.commentCount)
    }

    @Test
    fun `toPlayerArtifact should default missing commentCount to 0`() {
        val artifact = Artifact(
            id = "test-comment-default",
            title = "Default Comment Test",
            status = ArtifactStatus.ACTIVE
        )

        val playerArtifact = artifact.toPlayerArtifact()

        assertEquals(0L, playerArtifact.commentCount)
    }

    @Test
    fun `toPlayerArtifact should map savedCount correctly`() {
        val artifact = Artifact(
            id = "test-saved",
            title = "Saved Count Test",
            savedCount = 37L,
            status = ArtifactStatus.ACTIVE
        )

        val playerArtifact = artifact.toPlayerArtifact()

        assertEquals(37L, playerArtifact.savedCount)
    }

    @Test
    fun `toPlayerArtifact should default missing savedCount to 0`() {
        val artifact = Artifact(
            id = "test-saved-default",
            title = "Default Saved Count Test",
            status = ArtifactStatus.ACTIVE
        )

        val playerArtifact = artifact.toPlayerArtifact()

        assertEquals(0L, playerArtifact.savedCount)
    }

    @Test
    fun `toPlayerArtifact should keep savedCount independent from playCount, qualifiedPlayCount, reactionCount, and commentCount`() {
        val artifact = Artifact(
            id = "test-independent",
            title = "Independence Test",
            playCount = 1000L,
            qualifiedPlayCount = 50L,
            reactionCount = 25L,
            commentCount = 10L,
            savedCount = 5L,
            status = ArtifactStatus.ACTIVE
        )

        val playerArtifact = artifact.toPlayerArtifact()

        assertEquals(1000L, artifact.playCount)
        assertEquals(50L, playerArtifact.qualifiedPlayCount)
        assertEquals(10L, playerArtifact.commentCount)
        assertEquals(5L, playerArtifact.savedCount)
    }

    @Test
    fun `toPlayerArtifact should handle large valid savedCount without overflow or truncation`() {
        val largeCount = 10_000_000_000L
        val artifact = Artifact(
            id = "test-large-saved",
            title = "Large Saved Count Test",
            savedCount = largeCount,
            status = ArtifactStatus.ACTIVE
        )

        val playerArtifact = artifact.toPlayerArtifact()

        assertEquals(largeCount, playerArtifact.savedCount)
    }
}
