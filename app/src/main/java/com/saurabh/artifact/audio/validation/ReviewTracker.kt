package com.saurabh.artifact.audio.validation

import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.domain.review.ReviewPolicy
import java.util.BitSet

interface ReviewTracker {
    /** Called periodically during playback. */
    fun onPlaybackTick(currentPosMs: Long, realElapsedMs: Long, playbackSpeed: Float)
    
    /** Signals a jump in time, invalidating the "advancing normally" state. */
    fun onSeekPerformed(targetPosMs: Long)
    
    /** Marks the terminal state of the audio. */
    fun onPlaybackEnded()
    
    /** Returns current derived progress. */
    fun getProgress(): ReviewProgress
}

class DefaultReviewTracker(
    initialEvidence: EngagementEvidence,
    private val policy: ReviewPolicy,
    private val validator: ReviewValidator
) : ReviewTracker {

    private var currentEvidence = initialEvidence
    private var lastPlaybackPositionMs: Long = -1L

    override fun onPlaybackTick(currentPosMs: Long, realElapsedMs: Long, playbackSpeed: Float) {
        if (currentEvidence.durationMs <= 0) return

        // 1. Effort Tracking (Wall clock time categorized by speed)
        if (realElapsedMs > 0 && realElapsedMs < 5000) {
            val updatedEffortMap = currentEvidence.effortMap.toMutableMap()
            val currentEffort = updatedEffortMap.getOrDefault(playbackSpeed, 0L)
            updatedEffortMap[playbackSpeed] = currentEffort + realElapsedMs
            currentEvidence = currentEvidence.copy(effortMap = updatedEffortMap)
        }

        // 2. Coverage Tracking with Adaptive Scrub Tolerance
        val expectedDelta = (realElapsedMs * playbackSpeed).toLong()
        val tolerance = getScrubTolerance(currentEvidence.durationMs)
        
        val isAdvancingNormally = lastPlaybackPositionMs != -1L &&
                currentPosMs >= lastPlaybackPositionMs &&
                currentPosMs <= lastPlaybackPositionMs + expectedDelta + tolerance

        // Mark coverage if advancing normally or if tick is significant enough to trust
        if (isAdvancingNormally || realElapsedMs > 500) {
            val segmentSize = policy.getSegmentSizeMs(currentEvidence.durationMs)
            val segmentIndex = (currentPosMs / segmentSize).toInt()
            val totalSegments = (currentEvidence.durationMs / segmentSize).toInt().coerceAtLeast(1)
            
            if (segmentIndex < totalSegments) {
                val updatedCoverage = currentEvidence.coverage.clone() as BitSet
                updatedCoverage.set(segmentIndex)
                
                var updatedFurthest = currentEvidence.furthestPositionMs
                if (currentPosMs > updatedFurthest) {
                    updatedFurthest = currentPosMs
                }
                
                currentEvidence = currentEvidence.copy(
                    coverage = updatedCoverage,
                    lastPositionMs = currentPosMs,
                    furthestPositionMs = updatedFurthest,
                    lastUpdated = System.currentTimeMillis()
                )
            }
        }

        lastPlaybackPositionMs = currentPosMs
    }

    override fun onSeekPerformed(targetPosMs: Long) {
        lastPlaybackPositionMs = -1L // Break the "advancing normally" chain
    }

    override fun onPlaybackEnded() {
        currentEvidence = currentEvidence.copy(hasReachedEnd = true)
    }

    override fun getProgress(): ReviewProgress {
        val result = validator.validate(currentEvidence, policy)

        return ReviewProgress(
            artifactId = currentEvidence.artifactId,
            durationMs = currentEvidence.durationMs,
            coveragePercent = result.coveragePercent,
            effortPercent = result.effortPercent,
            hasReachedEnd = currentEvidence.hasReachedEnd,
            isValidationMet = result.isValid,
            evidence = currentEvidence,
            reviewResult = result
        )
    }

    private fun getScrubTolerance(durationMs: Long): Long {
        // 5% of duration, capped between 500ms and 3000ms
        return (durationMs * 0.05f).toLong().coerceIn(500L, 3000L)
    }
}
