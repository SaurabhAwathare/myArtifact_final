package com.saurabh.artifact.audio.validation

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
    private val artifactId: String,
    private val durationMs: Long,
    private val validator: ReviewValidator,
    initialP1: Long = 0L,
    initialP2: Long = 0L,
    initialEffortMs: Long = 0L,
    initialReachedEnd: Boolean = false,
    initialFurthestMs: Long = 0L
) : ReviewTracker {

    private var p1: Long = initialP1
    private var p2: Long = initialP2
    private var totalTimeListenedMs: Long = initialEffortMs
    private var hasReachedEnd: Boolean = initialReachedEnd
    private var furthestPositionMs: Long = initialFurthestMs
    
    private var lastPlaybackPositionMs: Long = -1L
    private val NUM_SEGMENTS = 100

    override fun onPlaybackTick(currentPosMs: Long, realElapsedMs: Long, playbackSpeed: Float) {
        if (durationMs <= 0) return

        // 1. Effort Tracking (Wall clock time)
        // Only count if playback is forward and speed is reasonable
        if (realElapsedMs > 0 && realElapsedMs < 5000) {
            totalTimeListenedMs += realElapsedMs
        }

        // 2. Coverage Tracking with Adaptive Scrub Tolerance
        val expectedDelta = (realElapsedMs * playbackSpeed).toLong()
        val tolerance = getScrubTolerance(durationMs)
        
        val isAdvancingNormally = lastPlaybackPositionMs != -1L &&
                currentPosMs >= lastPlaybackPositionMs &&
                currentPosMs <= lastPlaybackPositionMs + expectedDelta + tolerance

        if (isAdvancingNormally || realElapsedMs > 500) {
            val segmentIndex = (currentPosMs * NUM_SEGMENTS / durationMs).toInt().coerceIn(0, NUM_SEGMENTS - 1)
            if (segmentIndex < 64) {
                p1 = p1 or (1L shl segmentIndex)
            } else {
                p2 = p2 or (1L shl (segmentIndex - 64))
            }
            if (currentPosMs > furthestPositionMs) {
                furthestPositionMs = currentPosMs
            }
        }

        lastPlaybackPositionMs = currentPosMs
    }

    override fun onSeekPerformed(targetPosMs: Long) {
        lastPlaybackPositionMs = -1L // Break the "advancing normally" chain
    }

    override fun onPlaybackEnded() {
        hasReachedEnd = true
    }

    override fun getProgress(): ReviewProgress {
        val coverageCount = java.lang.Long.bitCount(p1) + java.lang.Long.bitCount(p2)
        val coveragePercent = coverageCount.toFloat() / NUM_SEGMENTS
        val effortPercent = totalTimeListenedMs.toFloat() / durationMs.coerceAtLeast(1)
        
        val result = validator.validate(
            coveragePercent = coveragePercent,
            effortPercent = effortPercent,
            reachedEnd = hasReachedEnd,
            durationMs = durationMs
        )

        return ReviewProgress(
            artifactId = artifactId,
            durationMs = durationMs,
            coveragePercent = coveragePercent,
            effortPercent = effortPercent,
            hasReachedEnd = hasReachedEnd,
            isValidationMet = result.isValid,
            rawP1 = p1,
            rawP2 = p2,
            totalTimeListenedMs = totalTimeListenedMs,
            furthestPositionMs = furthestPositionMs,
            reviewResult = result
        )
    }

    private fun getScrubTolerance(durationMs: Long): Long {
        // 5% of duration, capped between 500ms and 3000ms
        return (durationMs * 0.05f).toLong().coerceIn(500L, 3000L)
    }
}
