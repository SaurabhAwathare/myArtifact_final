package com.saurabh.artifact.domain

import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.ModerationMetadata
import com.saurabh.artifact.model.ModerationStatus
import com.saurabh.artifact.model.RecommendationState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyPolicyTest {

    private val safetyPolicy = SafetyPolicy()

    @Test
    fun `isEligibleForDiscovery should return false if creator is ignored`() {
        val artifact = createArtifact(status = ArtifactStatus.ACTIVE, userId = "userB")
        assertFalse(safetyPolicy.isEligibleForDiscovery(artifact, "userA", ignoredUserIds = setOf("userB")))
    }

    @Test
    fun `isEligibleForDiscovery should return true for active safe artifact`() {
        val artifact = createArtifact(status = ArtifactStatus.ACTIVE)
        assertTrue(safetyPolicy.isEligibleForDiscovery(artifact, "user1"))
    }

    @Test
    fun `isEligibleForDiscovery should return false if suppressed by user`() {
        val artifact = createArtifact(status = ArtifactStatus.ACTIVE)
        assertFalse(safetyPolicy.isEligibleForDiscovery(artifact, "user1", isSuppressedByUser = true))
    }

    @Test
    fun `isEligibleForDiscovery should return false if recommendationState is SUPPRESSED`() {
        val artifact = createArtifact(status = ArtifactStatus.ACTIVE, recommendationState = RecommendationState.SUPPRESSED)
        assertFalse(safetyPolicy.isEligibleForDiscovery(artifact, "user1"))
    }

    @Test
    fun `isEligibleForDiscovery should return false if reportCount is 3 or more`() {
        val artifact = createArtifact(status = ArtifactStatus.ACTIVE, reportCount = 3)
        assertFalse(safetyPolicy.isEligibleForDiscovery(artifact, "user1"))
    }

    @Test
    fun `isEligibleForDiscovery should return false if safetyConcernCount is 3 or more`() {
        val artifact = createArtifact(status = ArtifactStatus.ACTIVE, safetyConcernCount = 3)
        assertFalse(safetyPolicy.isEligibleForDiscovery(artifact, "user1"))
    }

    @Test
    fun `isEligibleForDiscovery should return false if globally HIDDEN`() {
        val artifact = createArtifact(status = ArtifactStatus.ACTIVE, moderationStatus = ModerationStatus.HIDDEN)
        assertFalse(safetyPolicy.isEligibleForDiscovery(artifact, "user1"))
    }

    @Test
    fun `isEligibleForPlayback should return true even if SUPPRESSED`() {
        val artifact = createArtifact(status = ArtifactStatus.ACTIVE, recommendationState = RecommendationState.SUPPRESSED)
        assertTrue(safetyPolicy.isEligibleForPlayback(artifact))
    }

    @Test
    fun `isEligibleForPlayback should return false if DELETED`() {
        val artifact = createArtifact(status = ArtifactStatus.DELETED)
        assertFalse(safetyPolicy.isEligibleForPlayback(artifact))
    }

    @Test
    fun `isEligibleForPlayback should return false if globally HIDDEN`() {
        val artifact = createArtifact(status = ArtifactStatus.ACTIVE, moderationStatus = ModerationStatus.HIDDEN)
        assertFalse(safetyPolicy.isEligibleForPlayback(artifact))
    }

    private fun createArtifact(
        status: ArtifactStatus = ArtifactStatus.ACTIVE,
        recommendationState: RecommendationState = RecommendationState.ACTIVE,
        reportCount: Long = 0,
        safetyConcernCount: Long = 0,
        moderationStatus: ModerationStatus = ModerationStatus.SAFE,
        audioUrl: String = "https://audio.com",
        userId: String = "creator"
    ): Artifact {
        return Artifact(
            id = "test-id",
            userId = userId,
            status = status,
            recommendationState = recommendationState,
            reportCount = reportCount,
            safetyConcernCount = safetyConcernCount,
            moderation = ModerationMetadata(status = moderationStatus),
            audioUrl = audioUrl
        )
    }
}
