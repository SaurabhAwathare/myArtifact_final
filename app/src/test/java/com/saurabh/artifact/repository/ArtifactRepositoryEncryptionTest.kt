package com.saurabh.artifact.repository

import com.google.firebase.Timestamp
import com.saurabh.artifact.data.local.ArtifactEntity
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.Emotion
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.Date

class ArtifactRepositoryEncryptionTest {

    private lateinit var repository: ArtifactRepository

    @Before
    fun setup() {
        // We only need the repository to access the internal mapping functions
        repository = ArtifactRepository(
            auth = mockk(relaxed = true),
            firestore = mockk(relaxed = true),
            storage = mockk(relaxed = true),
            draftDao = { mockk(relaxed = true) },
            userRepository = { mockk(relaxed = true) },
            artifactDao = { mockk(relaxed = true) },
            database = { mockk(relaxed = true) },
            artifactLibraryRepository = { mockk(relaxed = true) },
            moderationRepository = { mockk(relaxed = true) },
            publishingRepository = { mockk(relaxed = true) },
            artifactEngagementRepository = { mockk(relaxed = true) },
            reflectionPromptManager = { mockk(relaxed = true) },
            diagnosticLogger = mockk(relaxed = true)
        )
    }

    @Test
    fun `mapArtifactToEntity should preserve isEncrypted flag`() {
        val artifact = Artifact(
            id = "test_id",
            isEncrypted = true,
            status = ArtifactStatus.ACTIVE
        )

        val entity = repository.mapArtifactToEntity(artifact)

        assertTrue("Entity should be encrypted", entity.isEncrypted)
    }

    @Test
    fun `mapArtifactEntityToArtifact should restore isEncrypted flag`() {
        val entity = ArtifactEntity(
            id = "test_id",
            userId = "user_1",
            authorAnonymousId = "",
            authorName = "Author",
            authorSigil = "",
            authorSigilSeed = "",
            authorSigilColor = "",
            authorSigilConfigJson = "{}",
            audioUrl = "url",
            createdAt = System.currentTimeMillis(),
            durationMs = 1000,
            title = "Title",
            description = "",
            emotion = Emotion.NEUTRAL,
            emotionTag = "",
            playCount = 0,
            reactionCount = 0,
            amplitudeData = emptyList(),
            isEncrypted = true
        )

        val artifact = repository.mapArtifactEntityToArtifact(entity)

        assertTrue("Artifact should be encrypted", artifact.isEncrypted)
    }

    @Test
    fun `encryption round-trip through cache should preserve flag`() {
        val original = Artifact(
            id = "test_id",
            isEncrypted = true,
            status = ArtifactStatus.ACTIVE,
            createdAt = Timestamp(Date())
        )

        val entity = repository.mapArtifactToEntity(original)
        val restored = repository.mapArtifactEntityToArtifact(entity)

        assertTrue("Restored artifact should still be encrypted", restored.isEncrypted)
    }

    @Test
    fun `non-encrypted artifacts should remain non-encrypted`() {
        val original = Artifact(
            id = "plain_id",
            isEncrypted = false,
            status = ArtifactStatus.ACTIVE,
            createdAt = Timestamp(Date())
        )

        val entity = repository.mapArtifactToEntity(original)
        val restored = repository.mapArtifactEntityToArtifact(entity)

        assertFalse("Restored artifact should not be encrypted", restored.isEncrypted)
    }
}
