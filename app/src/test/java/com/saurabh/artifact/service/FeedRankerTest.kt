package com.saurabh.artifact.service

import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.UserSettings
import com.saurabh.artifact.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeedRankerTest {

    private lateinit var feedRanker: FeedRanker
    private val personalizationEngine = mockk<PersonalizationEngine>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val userSettingsFlow = MutableStateFlow(UserSettings())

    @Before
    fun setup() {
        every { settingsRepository.userSettings } returns userSettingsFlow
        every { personalizationEngine.userProfile } returns MutableStateFlow(UserPreferenceProfile())
        every { personalizationEngine.scoreContent(any(), any()) } returns 0.5
        feedRanker = FeedRanker(personalizationEngine, settingsRepository)
    }

    @Test
    fun `rank should prioritize personalization`() = runTest {
        val artifacts = listOf(
            createArtifact("1", "Calm"),
            createArtifact("2", "Angry")
        )
        
        // Mock personalization to favor "Calm"
        every { personalizationEngine.scoreContent("Calm", any()) } returns 1.0
        every { personalizationEngine.scoreContent("Angry", any()) } returns 0.0
        
        val ranked = feedRanker.rank(artifacts, null, null)
        
        assertEquals("1", ranked[0].id)
    }

    @Test
    fun `rank should apply emotion diversity`() = runTest {
        // Create 10 "Calm" artifacts and 10 "Happy" ones
        val calmArtifacts = (1..10).map { i -> createArtifact("C$i", "Calm") }
        val happyArtifacts = (1..10).map { i -> createArtifact("H$i", "Happy") }
        
        val mixed = (calmArtifacts + happyArtifacts).shuffled()
        
        val ranked = feedRanker.rank(mixed, null, null)
        
        // Verify no more than 2 of same emotion in any 3-window
        for (i in 0 until ranked.size - 2) {
            val window = ranked.subList(i, i + 3)
            val emotions = window.map { it.emotion }
            val counts = emotions.groupingBy { it }.eachCount()
            
            assertTrue("Window at $i ($emotions) has more than 2 of same emotion", counts.values.all { it <= 2 })
        }
    }

    private fun createArtifact(id: String, emotion: String, userId: String = "author"): Artifact {
        return Artifact(id = id, emotion = emotion, userId = userId)
    }
}
