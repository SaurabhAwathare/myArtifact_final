package com.saurabh.artifact.audio.validation

import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.domain.review.ReviewPolicy
import org.junit.Assert.*
import org.junit.Test

class ReviewTrackerTest {

    private val ruleEngine = DefaultReviewValidator()

    @Test
    fun `test normal playback completion`() {
        val duration = 10000L // 10s
        val evidence = EngagementEvidence("art1", "v1", duration)
        val policy = ReviewPolicy()
        val tracker = DefaultReviewTracker(evidence, policy, ruleEngine)
        
        // Simulating ticks every 100ms
        for (i in 0..100) {
            tracker.onPlaybackTick(i * 100L, 100L, 1.0f)
        }
        tracker.onPlaybackEnded()
        
        val progress = tracker.progress
        assertTrue("Should be validated after normal playback. Coverage: ${progress.coveragePercent}", progress.isValidationMet)
        assertTrue("Coverage should be high: ${progress.coveragePercent}", progress.coveragePercent >= 0.95f)
    }

    @Test
    fun `test coverage at high playback speed`() {
        val duration = 10000L // 10s
        val evidence = EngagementEvidence("art1", "v1", duration)
        val policy = ReviewPolicy()
        val tracker = DefaultReviewTracker(evidence, policy, ruleEngine)
        
        // Listen at 2x speed for the whole duration
        // Actual time spent = 5s
        for (i in 0..50) {
            tracker.onPlaybackTick(i * 200L, 100L, 2.0f)
        }
        tracker.onPlaybackEnded()
        
        val progress = tracker.progress
        assertTrue("Should be validated at 2x speed. Coverage: ${progress.coveragePercent}", progress.isValidationMet)
        assertTrue("Coverage should be high: ${progress.coveragePercent}", progress.coveragePercent >= 0.95f)
    }

    @Test
    fun `test seek-to-end bypass failure`() {
        val duration = 60000L // 60s
        val evidence = EngagementEvidence("art1", "v1", duration)
        val policy = ReviewPolicy()
        val tracker = DefaultReviewTracker(evidence, policy, ruleEngine)
        
        // 1. Play 1 second
        tracker.onPlaybackTick(1000L, 1000L, 1.0f)
        
        // 2. Seek to 59 seconds
        tracker.onSeekPerformed()
        tracker.onPlaybackTick(59000L, 100L, 1.0f)
        
        // 3. Finish
        tracker.onPlaybackTick(60000L, 1000L, 1.0f)
        tracker.onPlaybackEnded()
        
        val progress = tracker.progress
        assertFalse("Should NOT be validated if coverage is missing", progress.isValidationMet)
        assertTrue("Coverage should be low", progress.coveragePercent < 0.10f)
    }
}
