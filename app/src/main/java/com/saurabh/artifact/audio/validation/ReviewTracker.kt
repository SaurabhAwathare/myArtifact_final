package com.saurabh.artifact.audio.validation

import com.saurabh.artifact.domain.review.EngagementEvidence
import java.util.BitSet

interface ReviewTracker {
    /** Called periodically during playback. */
    fun onPlaybackTick(currentPosMs: Long, realElapsedMs: Long, playbackSpeed: Float)
    
    /** Signals a jump in time, invalidating the "advancing normally" state. */
    fun onSeekPerformed()
    
    /** Marks the terminal state of the audio. */
    fun onPlaybackEnded()
    
    /** Returns current derived progress. */
    val progress: ReviewProgress
}

/**
 * Robust implementation of the ReviewTracker.
 * Decoupled from specific Policy classes via functional hooks.
 */
class DefaultReviewTracker(
    initialEvidence: EngagementEvidence,
    private val segmentSizer: (Long) -> Long,
    private val validator: (EngagementEvidence) -> ReviewResult,
) : ReviewTracker {

    private var currentEvidence = initialEvidence
    private var lastPlaybackPositionMs: Long = -1L

    override fun onPlaybackTick(currentPosMs: Long, realElapsedMs: Long, playbackSpeed: Float) {
        if (currentEvidence.durationMs <= 0) return

        // 1. Coverage Tracking with Adaptive Speed-Aware Tolerance
        val expectedDelta = (realElapsedMs * playbackSpeed).toLong()
        val baseTolerance = getScrubTolerance(currentEvidence.durationMs)
        val speedLagBuffer = (800 * playbackSpeed).toLong() 
        
        val totalTolerance = baseTolerance + speedLagBuffer
        
        val isAdvancingNormally = (lastPlaybackPositionMs != -1L) &&
                (currentPosMs in lastPlaybackPositionMs..(lastPlaybackPositionMs + expectedDelta + totalTolerance))

        // Mark coverage only if advancing normally to prevent "painting" via seeks.
        if (isAdvancingNormally) {
            val segmentSize = segmentSizer(currentEvidence.durationMs)
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

    override fun onSeekPerformed() {
        lastPlaybackPositionMs = -1L // Break the "advancing normally" chain
    }

    override fun onPlaybackEnded() {
        currentEvidence = currentEvidence.copy(hasReachedEnd = true)
    }

    override val progress: ReviewProgress
        get() {
            val result = validator(currentEvidence)

            return ReviewProgress(
                artifactId = currentEvidence.artifactId,
                durationMs = currentEvidence.durationMs,
                coveragePercent = result.coveragePercent,
                hasReachedEnd = currentEvidence.hasReachedEnd,
                isValidationMet = result.isValid,
                evidence = currentEvidence,
                reviewResult = result,
            )
        }

    private fun getScrubTolerance(durationMs: Long): Long {
        // 5% of duration, capped between 500ms and 3000ms
        return (durationMs * 0.05f).toLong().coerceIn(500L, 3000L)
    }
}
