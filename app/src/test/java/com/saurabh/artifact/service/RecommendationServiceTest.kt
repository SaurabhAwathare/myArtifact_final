package com.saurabh.artifact.service

import com.google.firebase.Timestamp
import com.saurabh.artifact.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.*

class RecommendationServiceTest {

    private lateinit var service: RecommendationService
    private val safetyPolicy = com.saurabh.artifact.domain.SafetyPolicy()
    private val config = RecommendationConfig.DEFAULT

    @Before
    fun setup() {
        service = RecommendationService(safetyPolicy)
    }

    @Test
    fun `rank should filter out ineligible artifacts`() {
        val now = Timestamp.now()
        val artifacts = listOf(
            createArtifact("1", status = ArtifactStatus.ACTIVE, isPublic = true),
            createArtifact("2", status = ArtifactStatus.DRAFT, isPublic = true), // Draft
            createArtifact("3", status = ArtifactStatus.ACTIVE, isPublic = false), // Private
            createArtifact("4", recommendationState = RecommendationState.SUPPRESSED), // Suppressed
            createArtifact("5", moderationStatus = ModerationStatus.HIDDEN) // Hidden
        )

        val result = service.rank(artifacts)

        assertEquals(1, result.size)
        assertEquals("1", result[0].id)
    }

    @Test
    fun `rank should prioritize fresh artifacts`() {
        val now = Date()
        val oneHourAgo = Timestamp(Date(now.time - 3600 * 1000))
        val twoDaysAgo = Timestamp(Date(now.time - 2 * 24 * 3600 * 1000))

        val artifacts = listOf(
            createArtifact("old", createdAt = twoDaysAgo, playCount = 10),
            createArtifact("new", createdAt = oneHourAgo, playCount = 10)
        )

        val result = service.rank(artifacts)

        assertEquals("new", result[0].id)
        assertEquals("old", result[1].id)
    }

    @Test
    fun `rank should inject under-heard artifacts based on exploration ratio`() {
        val now = Timestamp.now()
        // Create 10 established artifacts
        val established = (1..10).map { 
            createArtifact("est_$it", createdAt = now, playCount = 100) 
        }
        // Create 2 under-heard artifacts
        val underHeard = (1..2).map { 
            createArtifact("uh_$it", createdAt = now, playCount = 1) 
        }

        val config = RecommendationConfig(explorationRatio = 0.2f) // 1 in 5
        val result = service.rank(candidates = established + underHeard, config = config)

        // Exploration ratio 0.2 means exploration interval is 5.
        // Item at index 5 and 11 (if exists) should be under-heard
        // Actually mergePools logic:
        // interval = 5. artifactsSinceLastExploration starts at 0.
        // Item 0-4 from main, Item 5 from exploration.
        
        assertTrue(result[5].playCount < config.underHeardThreshold)
    }

    @Test
    fun `rank should prevent consecutive artifacts from same creator`() {
        val now = Timestamp.now()
        val artifacts = listOf(
            createArtifact("1", userId = "userA", createdAt = now, playCount = 10),
            createArtifact("2", userId = "userA", createdAt = now, playCount = 10),
            createArtifact("3", userId = "userB", createdAt = now, playCount = 10)
        )

        val result = service.rank(artifacts)

        // Should reorder to A, B, A
        assertEquals("userA", result[0].userId)
        assertEquals("userB", result[1].userId)
        assertEquals("userA", result[2].userId)
    }

    private fun createArtifact(
        id: String,
        userId: String = "user_$id",
        status: ArtifactStatus = ArtifactStatus.ACTIVE,
        isPublic: Boolean = true,
        createdAt: Timestamp = Timestamp.now(),
        recommendationState: RecommendationState = RecommendationState.ACTIVE,
        moderationStatus: ModerationStatus = ModerationStatus.SAFE,
        playCount: Long = 10,
        reactionCount: Long = 0
    ): Artifact {
        return Artifact(
            id = id,
            userId = userId,
            status = status,
            isPublic = isPublic,
            createdAt = createdAt,
            recommendationState = recommendationState,
            moderation = ModerationMetadata(status = moderationStatus),
            playCount = playCount,
            reactionCount = reactionCount,
            audioUrl = "http://example.com/audio.mp3"
        )
    }
}
