package com.saurabh.artifact.audio.validation

import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.domain.review.ReviewTrackingVersion
import com.saurabh.artifact.domain.review.publishing.PublishingReviewPolicy
import com.saurabh.artifact.domain.review.publishing.PublishingReviewValidator
import org.junit.Assert.*
import org.junit.Test

class ReviewTrackerTest {

    private val ruleEngine = PublishingReviewValidator()

    @Test
    fun `test normal playback completion legacy`() {
        val duration = 10000L // 10s
        val evidence = EngagementEvidence("art1", "v1", duration, reviewTrackingVersion = ReviewTrackingVersion.LEGACY_BUCKETED)
        val policy = PublishingReviewPolicy()
        val tracker = DefaultReviewTracker(
            initialEvidence = evidence,
            segmentSizer = { dur, ver -> policy.getSegmentSizeMs(dur, ver) },
            validator = { ruleEngine.validate(it, policy) }
        )
        
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
    fun `test normal playback completion version 2`() {
        val duration = 10000L // 10s
        val evidence = EngagementEvidence("art1", "v1", duration, reviewTrackingVersion = ReviewTrackingVersion.FIXED_ONE_SECOND)
        val policy = PublishingReviewPolicy()
        val tracker = DefaultReviewTracker(
            initialEvidence = evidence,
            segmentSizer = { dur, ver -> policy.getSegmentSizeMs(dur, ver) },
            validator = { ruleEngine.validate(it, policy) }
        )
        
        // Simulating ticks every 100ms
        for (i in 0..100) {
            tracker.onPlaybackTick(i * 100L, 100L, 1.0f)
        }
        tracker.onPlaybackEnded()
        
        val progress = tracker.progress
        assertTrue("Should be validated in Version 2. Coverage: ${progress.coveragePercent}", progress.isValidationMet)
        // In version 2, 10s / 1s = 10 segments. 100 ticks at 100ms cover all 10s.
        assertTrue("Coverage should be high: ${progress.coveragePercent}", progress.coveragePercent >= 0.95f)
    }

    @Test
    fun `test coverage at high playback speed`() {
        val duration = 10000L // 10s
        val evidence = EngagementEvidence("art1", "v1", duration, reviewTrackingVersion = ReviewTrackingVersion.FIXED_ONE_SECOND)
        val policy = PublishingReviewPolicy()
        val tracker = DefaultReviewTracker(
            initialEvidence = evidence,
            segmentSizer = { dur, ver -> policy.getSegmentSizeMs(dur, ver) },
            validator = { ruleEngine.validate(it, policy) }
        )
        
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
        val evidence = EngagementEvidence("art1", "v1", duration, reviewTrackingVersion = ReviewTrackingVersion.FIXED_ONE_SECOND)
        val policy = PublishingReviewPolicy()
        val tracker = DefaultReviewTracker(
            initialEvidence = evidence,
            segmentSizer = { dur, ver -> policy.getSegmentSizeMs(dur, ver) },
            validator = { ruleEngine.validate(it, policy) }
        )
        
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

    @Test
    fun `test terminal position updates furthestPositionMs to durationMs without invalid segment index`() {
        val duration = 30000L // 30s
        val evidence = EngagementEvidence("art1", "v1", duration, reviewTrackingVersion = ReviewTrackingVersion.FIXED_ONE_SECOND)
        val policy = PublishingReviewPolicy()
        val tracker = DefaultReviewTracker(
            initialEvidence = evidence,
            segmentSizer = { dur, ver -> policy.getSegmentSizeMs(dur, ver) },
            validator = { ruleEngine.validate(it, policy) }
        )

        // 1. Tick normally until exact duration (30s)
        for (i in 0..300) {
            tracker.onPlaybackTick(i * 100L, 100L, 1.0f)
        }
        tracker.onPlaybackEnded()

        val progress = tracker.progress
        assertEquals("furthestPositionMs should equal durationMs at terminal position", duration, progress.evidence.furthestPositionMs)
        assertEquals("lastPositionMs should equal durationMs at terminal position", duration, progress.evidence.lastPositionMs)
        assertTrue("Coverage should be 100%", progress.coveragePercent >= 1.0f)
        assertTrue("Validation should be met", progress.isValidationMet)
    }

    @Test
    fun `test high speed playback terminal position updates furthestPositionMs to durationMs`() {
        val duration = 30000L // 30s
        val evidence = EngagementEvidence("art1", "v1", duration, reviewTrackingVersion = ReviewTrackingVersion.FIXED_ONE_SECOND)
        val policy = PublishingReviewPolicy()
        val tracker = DefaultReviewTracker(
            initialEvidence = evidence,
            segmentSizer = { dur, ver -> policy.getSegmentSizeMs(dur, ver) },
            validator = { ruleEngine.validate(it, policy) }
        )

        // Listen at 3x speed: tick interval 100ms, playback delta 300ms
        var pos = 0L
        while (pos < duration) {
            tracker.onPlaybackTick(pos, 100L, 3.0f)
            pos += 300L
        }
        // Terminal tick at exact duration
        tracker.onPlaybackTick(duration, 100L, 3.0f)
        tracker.onPlaybackEnded()

        val progress = tracker.progress
        assertEquals("furthestPositionMs should equal durationMs at 3x speed completion", duration, progress.evidence.furthestPositionMs)
        assertTrue("Validation should be met", progress.isValidationMet)
    }
}
