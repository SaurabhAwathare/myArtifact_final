package com.saurabh.artifact.domain

import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.ModerationMetadata
import com.saurabh.artifact.model.ModerationStatus
import com.saurabh.artifact.model.RecommendationState
import org.junit.Assert.assertFalse
import org.junit.Test

class SecondaryDiscoverySafetyTest {

    private val safetyPolicy = SafetyPolicy()

    @Test
    fun `Profile Discovery should exclude SUPPRESSED artifacts for other listeners`() {
        val artifact = createArtifact(recommendationState = RecommendationState.SUPPRESSED)
        // isEligibleForDiscovery should be false
        assertFalse(safetyPolicy.isEligibleForDiscovery(artifact, currentUserId = "other_user"))
    }

    @Test
    fun `Profile Discovery should exclude highly reported artifacts`() {
        val artifact = createArtifact(reportCount = 3L)
        assertFalse(safetyPolicy.isEligibleForDiscovery(artifact, currentUserId = "other_user"))
    }

    @Test
    fun `Profile Discovery should exclude artifacts with high safety concerns`() {
        val artifact = createArtifact(safetyConcernCount = 3L)
        assertFalse(safetyPolicy.isEligibleForDiscovery(artifact, currentUserId = "other_user"))
    }

    @Test
    fun `Profile Discovery should exclude globally HIDDEN artifacts`() {
        val artifact = createArtifact(moderationStatus = ModerationStatus.HIDDEN)
        assertFalse(safetyPolicy.isEligibleForDiscovery(artifact, currentUserId = "other_user"))
    }

    @Test
    fun `Saved Library should apply same safety thresholds as primary feeds`() {
        val suppressed = createArtifact(recommendationState = RecommendationState.SUPPRESSED)
        val reported = createArtifact(reportCount = 3L)
        val hidden = createArtifact(moderationStatus = ModerationStatus.HIDDEN)
        
        assertFalse("Suppressed should be ineligible for discovery library", 
            safetyPolicy.isEligibleForDiscovery(suppressed, currentUserId = "user1"))
        assertFalse("Reported should be ineligible for discovery library", 
            safetyPolicy.isEligibleForDiscovery(reported, currentUserId = "user1"))
        assertFalse("Hidden should be ineligible for discovery library", 
            safetyPolicy.isEligibleForDiscovery(hidden, currentUserId = "user1"))
    }

    private fun createArtifact(
        status: ArtifactStatus = ArtifactStatus.ACTIVE,
        recommendationState: RecommendationState = RecommendationState.ACTIVE,
        reportCount: Long = 0,
        safetyConcernCount: Long = 0,
        moderationStatus: ModerationStatus = ModerationStatus.SAFE,
        audioUrl: String = "https://audio.com"
    ): Artifact {
        return Artifact(
            id = "test-id",
            status = status,
            recommendationState = recommendationState,
            reportCount = reportCount,
            safetyConcernCount = safetyConcernCount,
            moderation = ModerationMetadata(status = moderationStatus),
            audioUrl = audioUrl
        )
    }
}
