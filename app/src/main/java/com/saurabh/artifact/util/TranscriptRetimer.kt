package com.saurabh.artifact.util

import com.saurabh.artifact.model.*

object TranscriptRetimer {

    /**
     * Recalculates timestamps for all segments after a set of REMOVE operations.
     * MUTE and REDACT operations do not affect timing.
     */
    fun retimeTranscript(
        originalSegments: List<TranscriptSegment>,
        activeOperations: List<SemanticEditOperation>
    ): List<TranscriptSegment> {
        val removeOperations = activeOperations.filter { it.action == EditAction.REMOVE }
        if (removeOperations.isEmpty()) return originalSegments

        // Identify all segments that are explicitly removed
        val removedSegmentIds = removeOperations.flatMap { it.segmentIds }.toSet()
        
        // Filter out removed segments
        val remainingSegments = originalSegments.filter { it.id !in removedSegmentIds }

        // Sort by original start time to process sequentially
        val sortedSegments = remainingSegments.sortedBy { it.startMs }

        var currentTimeShift = 0L
        val retimedSegments = mutableListOf<TranscriptSegment>()

        // This is a simplified version. For production, we'd need to handle 
        // the exact time gaps between segments if they aren't contiguous.
        // Here we assume we want to maintain relative gaps.
        
        // Better approach: Calculate the total duration removed before each segment.
        val removedIntervals = originalSegments
            .filter { it.id in removedSegmentIds }
            .map { it.startMs to it.endMs }
            .sortedBy { it.first }

        return sortedSegments.map { segment ->
            val shiftForThisSegment = calculateShift(segment.startMs, removedIntervals)
            segment.copy(
                startMs = segment.startMs - shiftForThisSegment,
                endMs = segment.endMs - shiftForThisSegment
            )
        }
    }

    private fun calculateShift(originalTime: Long, removedIntervals: List<Pair<Long, Long>>): Long {
        var totalShift = 0L
        for (interval in removedIntervals) {
            if (interval.second <= originalTime) {
                // Entire interval was before this time
                totalShift += (interval.second - interval.first)
            } else if (interval.first < originalTime) {
                // Interval overlaps with the start of this segment (shouldn't happen with clean segment cuts)
                totalShift += (originalTime - interval.first)
            }
        }
        return totalShift
    }

    /**
     * Generates a mapping for playback syncing.
     */
    fun generatePlaybackMap(
        originalSegments: List<TranscriptSegment>,
        activeOperations: List<SemanticEditOperation>
    ): PlaybackMap {
        val retimed = retimeTranscript(originalSegments, activeOperations)
        val mutedIds = activeOperations.filter { it.action != EditAction.REMOVE }.flatMap { it.segmentIds }.toSet()
        
        val mappings = retimed.mapIndexed { index, retimedSegment ->
            val original = originalSegments.find { it.id == retimedSegment.id }!!
            PlaybackSegmentMapping(
                virtualStartMs = retimedSegment.startMs,
                virtualEndMs = retimedSegment.endMs,
                originalStartMs = original.startMs,
                originalEndMs = original.endMs,
                isMuted = original.id in mutedIds
            )
        }
        
        return PlaybackMap(mappings)
    }
}
