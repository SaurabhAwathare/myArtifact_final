package com.saurabh.artifact.service

import com.saurabh.artifact.model.FeedArtifact
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PacingEngine @Inject constructor() {

    /**
     * Re-orders the feed to ensure emotional variety and prevent overstimulation.
     * Heuristic: Avoid more than 2 "High Intensity" artifacts in a row.
     * Complexity: O(N) time, O(N) space.
     */
    fun paceFeed(items: List<FeedArtifact>): List<FeedArtifact> {
        if (items.size < 3) return items

        val paced = mutableListOf<FeedArtifact>()
        val remaining = items.toMutableList()
        var intenseStreak = 0

        while (remaining.isNotEmpty()) {
            val nextIndex = if (intenseStreak >= 2) {
                // Find a "Low Intensity" artifact
                val calmIndex = remaining.indexOfFirst { !isHighIntensity(it) }
                if (calmIndex != -1) calmIndex else 0
            } else {
                0
            }

            val item = remaining.removeAt(nextIndex)
            paced.add(item)

            if (isHighIntensity(item)) {
                intenseStreak++
            } else {
                intenseStreak = 0
            }
        }

        return paced
    }

    private fun isHighIntensity(item: FeedArtifact): Boolean {
        return when (item.artifact.emotion.uppercase()) {
            "ANGRY", "ANXIOUS", "SAD" -> true
            else -> false
        }
    }
}
