package com.saurabh.artifact.service

import com.google.firebase.Timestamp
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactStats
import com.saurabh.artifact.model.ArtifactStatus
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.log10

class ResonanceDepthRankingTest {

    private val recommendationService = RecommendationService(mockk(relaxed = true))
    private val config = RecommendationConfig.DEFAULT

    @Test
    fun `calculateResonanceDepth returns smoothed score`() {
        // 1 Listener, 100% completion (4 milestones)
        val statsOne = ArtifactStats(
            milestones = mapOf("25" to 1L, "50" to 1L, "75" to 1L, "100" to 1L),
            uniqueListeners = 1L
        )
        // (4 + 10) / (4 + 20) = 14 / 24 = 0.5833
        assertEquals(0.5833f, statsOne.calculateResonanceDepth(), 0.001f)

        // 10 Listeners, 100% completion (40 milestones)
        val statsTen = ArtifactStats(
            milestones = mapOf("25" to 10L, "50" to 10L, "75" to 10L, "100" to 10L),
            uniqueListeners = 10L
        )
        // (40 + 10) / (40 + 20) = 50 / 60 = 0.8333
        assertEquals(0.8333f, statsTen.calculateResonanceDepth(), 0.001f)
    }

    @Test
    fun `calculateResonanceDepth handles zero listeners with prior`() {
        val stats = ArtifactStats(uniqueListeners = 0L)
        // (0 + 10) / (0 + 20) = 0.5
        assertEquals(0.5f, stats.calculateResonanceDepth(), 0.001f)
    }

    @Test
    fun `ranking prioritizes resonance depth with hardened weights`() {
        val now = Timestamp.now()
        
        // Artifact A: 100 Reactions, Low Depth (10%), Short (30s)
        val artifactA = Artifact(
            id = "A",
            createdAt = now,
            reactionCount = 100L,
            resonanceDepth = 0.1f,
            durationMs = 30000L, // 30s
            status = ArtifactStatus.ACTIVE,
            audioUrl = "url"
        )
        
        // Artifact B: 0 Reactions, High Depth (90%), Long (5m)
        val artifactB = Artifact(
            id = "B",
            createdAt = now,
            reactionCount = 0L,
            resonanceDepth = 0.9f,
            durationMs = 300000L, // 5m
            status = ArtifactStatus.ACTIVE,
            audioUrl = "url"
        )
        
        // Calculation model:
        // Score A = Recency + (0.1 * 5.0 * log10(31)) + log10(101)
        // log10(31) approx 1.49. log10(101) approx 2.0
        // Score A approx Recency + (0.745) + 2.0 = Recency + 2.745
        
        // Score B = Recency + (0.9 * 5.0 * log10(301)) + log10(1)
        // log10(301) approx 2.47. log10(1) = 0
        // Score B approx Recency + (4.5 * 2.47) + 0 = Recency + 11.115
        
        val ranked = recommendationService.rank(listOf(artifactA, artifactB))
        // B should win by a large margin due to depth and duration normalization
        assertEquals("B", ranked[0].id)
    }

    @Test
    fun `diminishing returns of reactions via log10`() {
        val now = Timestamp.now()
        
        val artifactA = Artifact(id = "A", createdAt = now, reactionCount = 10L, resonanceDepth = 0.5f, status = ArtifactStatus.ACTIVE, audioUrl = "url")
        val artifactB = Artifact(id = "B", createdAt = now, reactionCount = 100L, resonanceDepth = 0.5f, status = ArtifactStatus.ACTIVE, audioUrl = "url")
        
        // log10(11) approx 1.04
        // log10(101) approx 2.0
        // Score difference is approx 0.96 despite 10x more reactions.
        
        val ranked = recommendationService.rank(listOf(artifactA, artifactB))
        assertEquals("B", ranked[0].id)
    }

    @Test
    fun `duration factor prevents snackable content bias`() {
        val now = Timestamp.now()
        
        // Short artifact (30s) with 100% depth
        val short = Artifact(id = "short", createdAt = now, resonanceDepth = 1.0f, durationMs = 30000L, status = ArtifactStatus.ACTIVE, audioUrl = "url")
        
        // Long artifact (10m) with 70% depth
        val long = Artifact(id = "long", createdAt = now, resonanceDepth = 0.7f, durationMs = 600000L, status = ArtifactStatus.ACTIVE, audioUrl = "url")
        
        // Score Short: 1.0 * 5.0 * log10(31) approx 5.0 * 1.49 = 7.45
        // Score Long: 0.7 * 5.0 * log10(601) approx 3.5 * 2.77 = 9.695
        
        val ranked = recommendationService.rank(listOf(short, long))
        assertEquals("long", ranked[0].id)
    }
}
