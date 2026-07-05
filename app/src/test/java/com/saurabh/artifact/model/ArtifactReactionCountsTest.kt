package com.saurabh.artifact.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtifactReactionCountsTest {

    @Test
    fun `getFuzzySummary should return empty for HIDDEN visibility`() {
        val counts = ArtifactReactionCounts(totalCount = 10, visibility = ReactionVisibilityMode.HIDDEN)
        assertEquals("", counts.getFuzzySummary())
    }

    @Test
    fun `getFuzzySummary should return empty for CREATOR_ONLY visibility if not owner`() {
        val counts = ArtifactReactionCounts(totalCount = 10, visibility = ReactionVisibilityMode.CREATOR_ONLY)
        assertEquals("", counts.getFuzzySummary(isOwner = false))
    }

    @Test
    fun `getFuzzySummary should return summary for CREATOR_ONLY visibility if owner`() {
        val counts = ArtifactReactionCounts(totalCount = 10, visibility = ReactionVisibilityMode.CREATOR_ONLY)
        val summary = counts.getFuzzySummary(isOwner = true)
        assert(summary.isNotEmpty())
        assert(summary.contains("Many have found resonance"))
    }

    @Test
    fun `getFuzzySummary should return exact count message for VISIBLE visibility`() {
        val counts = ArtifactReactionCounts(totalCount = 1, visibility = ReactionVisibilityMode.VISIBLE)
        assertEquals("Another soul felt this", counts.getFuzzySummary())
        
        val counts2 = ArtifactReactionCounts(totalCount = 42, visibility = ReactionVisibilityMode.VISIBLE)
        assertEquals("42 souls felt this too", counts2.getFuzzySummary())
    }

    @Test
    fun `getFuzzySummary should return fuzzy messages for APPROXIMATE visibility`() {
        val counts1 = ArtifactReactionCounts(totalCount = 1, visibility = ReactionVisibilityMode.APPROXIMATE)
        assertEquals("Another soul felt this", counts1.getFuzzySummary())
        
        val counts2 = ArtifactReactionCounts(totalCount = 3, visibility = ReactionVisibilityMode.APPROXIMATE)
        assertEquals("A few people are holding space here", counts2.getFuzzySummary())
        
        val counts3 = ArtifactReactionCounts(totalCount = 15, visibility = ReactionVisibilityMode.APPROXIMATE)
        assertEquals("Many have found resonance in your words", counts3.getFuzzySummary())
        
        val counts4 = ArtifactReactionCounts(totalCount = 100, visibility = ReactionVisibilityMode.APPROXIMATE)
        assertEquals("A vast echo is returning to you", counts4.getFuzzySummary())
    }

    @Test
    fun `getFuzzySummary should return empty for zero counts`() {
        val counts = ArtifactReactionCounts(totalCount = 0, visibility = ReactionVisibilityMode.VISIBLE)
        assertEquals("", counts.getFuzzySummary())
    }
}
