package com.saurabh.artifact.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtifactReactionCountsTest {

    @Test
    fun `getFuzzySummary should return empty for HIDDEN visibility`() {
        val counts = ArtifactReactionCounts(totalCount = 10L, visibility = ReactionVisibilityMode.HIDDEN)
        assertEquals("", counts.getFuzzySummary())
    }

    @Test
    fun `getFuzzySummary should return empty for CREATOR_ONLY visibility if not owner`() {
        val counts = ArtifactReactionCounts(totalCount = 10L, visibility = ReactionVisibilityMode.CREATOR_ONLY)
        assertEquals("", counts.getFuzzySummary(isOwner = false))
    }

    @Test
    fun `getFuzzySummary should return summary for CREATOR_ONLY visibility if owner`() {
        val counts = ArtifactReactionCounts(totalCount = 10L, visibility = ReactionVisibilityMode.CREATOR_ONLY)
        val summary = counts.getFuzzySummary(isOwner = true)
        assertEquals("10 Resonators", summary)
    }

    @Test
    fun `getFuzzySummary should return exact count message for VISIBLE visibility`() {
        val counts = ArtifactReactionCounts(totalCount = 1L, visibility = ReactionVisibilityMode.VISIBLE)
        assertEquals("1 Resonator", counts.getFuzzySummary())
        
        val counts2 = ArtifactReactionCounts(totalCount = 42L, visibility = ReactionVisibilityMode.VISIBLE)
        assertEquals("42 Resonators", counts2.getFuzzySummary())
    }

    @Test
    fun `getFuzzySummary should return exact count message for APPROXIMATE visibility`() {
        val counts1 = ArtifactReactionCounts(totalCount = 1L, visibility = ReactionVisibilityMode.APPROXIMATE)
        assertEquals("1 Resonator", counts1.getFuzzySummary())
        
        val counts2 = ArtifactReactionCounts(totalCount = 3L, visibility = ReactionVisibilityMode.APPROXIMATE)
        assertEquals("3 Resonators", counts2.getFuzzySummary())
        
        val counts3 = ArtifactReactionCounts(totalCount = 15L, visibility = ReactionVisibilityMode.APPROXIMATE)
        assertEquals("15 Resonators", counts3.getFuzzySummary())
        
        val counts4 = ArtifactReactionCounts(totalCount = 100L, visibility = ReactionVisibilityMode.APPROXIMATE)
        assertEquals("100 Resonators", counts4.getFuzzySummary())
    }

    @Test
    fun `getFuzzySummary should return empty for zero counts`() {
        val counts = ArtifactReactionCounts(totalCount = 0L, visibility = ReactionVisibilityMode.VISIBLE)
        assertEquals("", counts.getFuzzySummary())
    }
}
