package com.saurabh.artifact.audio.validation

import org.junit.Assert.*
import org.junit.Test

class ReviewTrackerTest {

    private val ruleEngine = DefaultReviewValidator()

    @Test
    fun `test normal playback completion`() {
        val duration = 10000L // 10s
        val tracker = DefaultReviewTracker("art1", duration, ruleEngine)
        
        // Simulating ticks every 100ms
        for (i in 0..100) {
            tracker.onPlaybackTick(i * 100L, 100L, 1.0f)
        }
        tracker.onPlaybackEnded()
        
        val progress = tracker.getProgress()
        assertTrue("Should be validated after normal playback. Coverage: ${progress.coveragePercent}", progress.isValidationMet)
        assertTrue("Coverage should be high: ${progress.coveragePercent}", progress.coveragePercent >= 0.95f)
        assertTrue(progress.effortPercent >= 0.75f)
    }

    @Test
    fun `test seek-to-end bypass failure`() {
        val duration = 60000L // 60s
        val tracker = DefaultReviewTracker("art1", duration, ruleEngine)
        
        // 1. Play 1 second
        tracker.onPlaybackTick(1000L, 1000L, 1.0f)
        
        // 2. Seek to 59 seconds
        tracker.onSeekPerformed(59000L)
        tracker.onPlaybackTick(59000L, 100L, 1.0f)
        
        // 3. Finish
        tracker.onPlaybackTick(60000L, 1000L, 1.0f)
        tracker.onPlaybackEnded()
        
        val progress = tracker.getProgress()
        assertFalse("Should NOT be validated if effort is low and coverage is missing", progress.isValidationMet)
        assertTrue("Coverage should be low", progress.coveragePercent < 0.20f)
    }
}
