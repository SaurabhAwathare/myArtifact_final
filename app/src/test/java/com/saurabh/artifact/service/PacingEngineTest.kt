package com.saurabh.artifact.service

import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.FeedArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacingEngineTest {

    private val pacingEngine = PacingEngine()

    @Test
    fun `paceFeed should prevent more than 2 high intensity items in a row`() {
        // High Intensity: ANGRY, ANXIOUS, SAD
        val items = listOf(
            createFeedArtifact("1", "ANGRY"),
            createFeedArtifact("2", "ANXIOUS"),
            createFeedArtifact("3", "SAD"),
            createFeedArtifact("4", "CALM"),
            createFeedArtifact("5", "ANGRY"),
            createFeedArtifact("6", "ANXIOUS"),
            createFeedArtifact("7", "SAD")
        )

        val paced = pacingEngine.paceFeed(items)

        // Verify pacing
        var intenseStreak = 0
        for (item in paced) {
            val isHigh = isHighIntensity(item)
            if (isHigh) {
                intenseStreak++
            } else {
                intenseStreak = 0
            }
            println("Item: ${item.artifact.id}, Emotion: ${item.artifact.emotion}, IntenseStreak: $intenseStreak")
            assertTrue("Intense streak exceeded at item ${item.artifact.id} (${item.artifact.emotion})", intenseStreak <= 2)
        }

        // Verify all items are preserved
        assertEquals(items.size, paced.size)
        assertEquals(items.toSet(), paced.toSet())
    }

    @Test
    fun `paceFeed should handle all low intensity items`() {
        val items = (1..5).map { createFeedArtifact(it.toString(), "CALM") }
        val paced = pacingEngine.paceFeed(items)
        assertEquals(items, paced)
    }

    @Test
    fun `paceFeed should handle all high intensity items (force placement if no low)`() {
        val items = (1..5).map { createFeedArtifact(it.toString(), "ANGRY") }
        val paced = pacingEngine.paceFeed(items)
        
        // When there are NO low intensity items, it should just return them (maybe clustered) 
        // because it has no other choice in the simplified logic.
        assertEquals(items.size, paced.size)
    }

    @Test
    fun `paceFeed should handle small lists`() {
        val items = listOf(createFeedArtifact("1", "ANGRY"), createFeedArtifact("2", "ANXIOUS"))
        assertEquals(items, pacingEngine.paceFeed(items))
    }

    private fun createFeedArtifact(id: String, emotion: String): FeedArtifact {
        return FeedArtifact(artifact = Artifact(id = id, emotion = emotion))
    }

    private fun isHighIntensity(item: FeedArtifact): Boolean {
        return when (item.artifact.emotion.uppercase()) {
            "ANGRY", "ANXIOUS", "SAD" -> true
            else -> false
        }
    }
}
