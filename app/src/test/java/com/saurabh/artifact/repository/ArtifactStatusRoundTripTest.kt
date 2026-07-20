package com.saurabh.artifact.repository

import com.google.firebase.Timestamp
import com.saurabh.artifact.data.local.ArtifactEntity
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.Emotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Date

class ArtifactStatusRoundTripTest {

    @Test
    fun testArtifactToEntityToArtifactRoundTrip() {
        // 1. Create a published Artifact (as if fetched from Firestore)
        val originalArtifact = Artifact(
            id = "test_artifact",
            userId = "user_123",
            status = ArtifactStatus.ACTIVE,
            isDraftField = false, // isDraftField maps to isDraft in Firestore
            title = "Test Title",
            createdAt = Timestamp(Date())
        )

        // 2. Map to Entity (as if caching in Room)
        val entity = mapArtifactToEntity(originalArtifact)
        
        // Assertions on Entity
        assertEquals(ArtifactStatus.ACTIVE, entity.status)
        assertFalse(entity.isDraft)

        // 3. Map back to Artifact (as if reading from Room for UI)
        val restoredArtifact = mapArtifactEntityToArtifact(entity)

        // 4. Final Assertions
        assertEquals(ArtifactStatus.ACTIVE, restoredArtifact.status)
        assertFalse("Restored artifact should not be a draft", restoredArtifact.isDraft)
        assertEquals(ArtifactStatus.ACTIVE, restoredArtifact.status)
    }

    // Helper functions copied from ArtifactRepository for isolated testing of mapping logic
    private fun mapArtifactEntityToArtifact(entity: ArtifactEntity): Artifact {
        return Artifact(
            id = entity.id,
            userId = entity.userId,
            author = AuthorSnapshot(
                anonymousId = entity.authorAnonymousId,
                name = entity.authorName,
                sigil = entity.authorSigil,
                avatarSeed = entity.authorAvatarSeed,
                avatarColor = entity.authorAvatarColor
            ),
            audioUrl = entity.audioUrl,
            createdAt = Timestamp(Date(entity.createdAt)),
            durationMs = entity.durationMs,
            title = entity.title,
            description = entity.description,
            emotion = entity.emotion.label,
            emotionTag = entity.emotionTag,
            playCount = entity.playCount,
            reactionCount = entity.reactionCount,
            reportCount = entity.reportCount,
            safetyConcernCount = entity.safetyConcernCount,
            reporterIds = entity.reporterIds,
            amplitudeData = entity.amplitudeData,
            transcriptUrl = entity.transcriptUrl,
            status = entity.status,
            isDraftField = entity.isDraft
        )
    }

    private fun mapArtifactToEntity(artifact: Artifact): ArtifactEntity {
        return ArtifactEntity(
            id = artifact.id,
            userId = artifact.userId,
            authorAnonymousId = artifact.author.anonymousId,
            authorName = artifact.author.name,
            authorSigil = artifact.author.sigil,
            authorAvatarSeed = artifact.author.avatarSeed,
            authorAvatarColor = artifact.author.avatarColor,
            authorAvatarConfigJson = "", // Not needed for this test
            audioUrl = artifact.audioUrl,
            createdAt = artifact.createdAt.toDate().time,
            durationMs = artifact.durationMs,
            title = artifact.title,
            description = artifact.description,
            emotion = Emotion.entries.find { 
                it.label.equals(artifact.emotion, ignoreCase = true) ||
                it.name.equals(artifact.emotion, ignoreCase = true)
            } ?: Emotion.NEUTRAL,
            primaryStyle = null,
            emotionTag = artifact.emotionTag,
            playCount = artifact.playCount,
            reactionCount = artifact.reactionCount,
            reportCount = artifact.reportCount,
            safetyConcernCount = artifact.safetyConcernCount,
            reporterIds = artifact.reporterIds,
            amplitudeData = artifact.amplitudeData,
            transcriptUrl = artifact.transcriptUrl,
            status = artifact.status,
            isDraft = artifact.isDraft,
            lastUpdated = System.currentTimeMillis()
        )
    }
}
