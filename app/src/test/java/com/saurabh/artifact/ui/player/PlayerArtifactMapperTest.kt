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
            status = ArtifactStatus.ACTIVE
        )

        val playerArtifact = artifact.toPlayerArtifact()

        assertEquals("test-id", playerArtifact.id)
        assertEquals("Test Title", playerArtifact.title)
        assertEquals(true, playerArtifact.isPublic)
    }
}
