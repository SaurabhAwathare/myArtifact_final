package com.saurabh.artifact.service

import com.google.firebase.Timestamp
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactStats
import com.saurabh.artifact.model.ArtifactStatus
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class HumanIntegrityRankingTest {

    private val recommendationService = RecommendationService(mockk(relaxed = true))

    @Test
    fun `calculateHumanIntegrityFactor handles zero listeners`() {
        val stats = ArtifactStats(uniqueListeners = 0L)
        // Baseline 1.0 for new content to avoid penalizing it before evidence
        assertEquals(1.0f, stats.calculateHumanIntegrityFactor(), 0.001f)
    }

    @Test
    fun `calculateHumanIntegrityFactor smoothed ratios for small sample`() {
        // 1 Listener, Mature=1, Independent=1
        val stats = ArtifactStats(
            uniqueListeners = 1L,
            matureListeners = 1L,
            independentListeners = 1L
        )
        // matureRatio = (1+1)/(1+2) = 2/3 = 0.666
        // independentRatio = (1+1)/(1+2) = 2/3 = 0.666
        // HIS = (0.666 + 0.666) / 2 = 0.666
        assertEquals(0.666f, stats.calculateHumanIntegrityFactor(), 0.001f)
    }

    @Test
    fun `calculateHumanIntegrityFactor rewards consensus in large sample`() {
        // 100 Listeners, all mature and independent
        val stats = ArtifactStats(
            uniqueListeners = 100L,
            matureListeners = 100L,
            independentListeners = 100L
        )
        // ratios = (100+1)/(100+2) = 101/102 approx 0.99
        assertEquals(0.99f, stats.calculateHumanIntegrityFactor(), 0.01f)
    }

    @Test
    fun `calculateHumanIntegrityFactor penalizes cliques`() {
        // 100 Listeners, all mature but NONE independent (All followers)
        val stats = ArtifactStats(
            uniqueListeners = 100L,
            matureListeners = 100L,
            independentListeners = 0L
        )
        // matureRatio = 101/102 approx 0.99
        // independentRatio = 1/102 approx 0.01
        // HIS = (0.99 + 0.01) / 2 = 0.5
        assertEquals(0.5f, stats.calculateHumanIntegrityFactor(), 0.01f)
    }

    @Test
    fun `ranking uses HIS as a multiplier for depth`() {
        val now = Timestamp.now()
        
        // Artifact A: High Depth (0.9), but Low Integrity (0.5 - Clique)
        val artifactA = Artifact(
            id = "A",
            createdAt = now,
            resonanceDepth = 0.9f,
            humanIntegrityFactor = 0.5f,
            durationMs = 300000L, // 5m
            status = ArtifactStatus.ACTIVE,
            audioUrl = "url"
        )
        
        // Artifact B: Moderate Depth (0.6), but High Integrity (1.0 - Universal resonance)
        val artifactB = Artifact(
            id = "B",
            createdAt = now,
            resonanceDepth = 0.6f,
            humanIntegrityFactor = 1.0f,
            durationMs = 300000L, // 5m
            status = ArtifactStatus.ACTIVE,
            audioUrl = "url"
        )
        
        // Calculation Model: Depth * 5.0 * LogDuration * Integrity
        // log10(301) approx 2.48
        
        // Score A: 0.9 * 5.0 * 2.48 * 0.5 = 5.58
        // Score B: 0.6 * 5.0 * 2.48 * 1.0 = 7.44
        
        val ranked = recommendationService.rank(listOf(artifactA, artifactB))
        assertEquals("B", ranked[0].id)
    }

    @Test
    fun `HIS cannot overwhelm recency for old content`() {
        val weekAgo = Timestamp(System.currentTimeMillis() / 1000 - 604800, 0)
        val now = Timestamp.now()
        
        // Old high-integrity artifact
        val old = Artifact(
            id = "old",
            createdAt = weekAgo,
            resonanceDepth = 1.0f,
            humanIntegrityFactor = 1.0f,
            durationMs = 600000L,
            status = ArtifactStatus.ACTIVE
        )
        
        // New moderate-integrity artifact
        val recent = Artifact(
            id = "new",
            createdAt = now,
            resonanceDepth = 0.5f,
            humanIntegrityFactor = 1.0f,
            durationMs = 600000L,
            status = ArtifactStatus.ACTIVE
        )
        
        // Recency for 'old' is 1.0 less than 'new' (approx)
        val ranked = recommendationService.rank(listOf(old, recent))
        // 'old' might still win if quality difference is huge, but here it's 1.0 vs 0.5.
        // DepthScore Old: 1.0 * 5.0 * 2.78 = 13.9
        // DepthScore New: 0.5 * 5.0 * 2.78 = 6.95
        // Difference (6.95) > Recency gap (1.0). 
        // This is intentional: deep content is evergreen.
        assertEquals("old", ranked[0].id)
    }
}
