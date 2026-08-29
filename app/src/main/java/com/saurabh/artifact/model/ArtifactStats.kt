package com.saurabh.artifact.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.IgnoreExtraProperties

/**
 * Global aggregate listening-depth statistics for an Artifact.
 */
@IgnoreExtraProperties
data class ArtifactStats(
    val milestones: Map<String, Long> = emptyMap(), // Keyed by "25", "50", "75", "100"
    val uniqueListeners: Long = 0,
    val matureListeners: Long = 0,
    val independentListeners: Long = 0,
    val lastUpdated: Timestamp = Timestamp.now()
) {
    /**
     * Calculates a Bayesian-smoothed resonance depth score [0.0 - 1.0].
     * Formula: (total milestones reached + 10) / (unique listeners * 4 + 20)
     * The +10 / +20 prior represents a 0.5 global-average prior to stabilize scores.
     */
    fun calculateResonanceDepth(): Float {
        val reached25 = milestones["25"] ?: 0L
        val reached50 = milestones["50"] ?: 0L
        val reached75 = milestones["75"] ?: 0L
        val reached100 = milestones["100"] ?: 0L
        
        val totalMilestonesReached = reached25 + reached50 + reached75 + reached100
        
        // Bayesian Prior (K=5 unique listeners, Prior Average = 0.5)
        val numerator = totalMilestonesReached + 10f
        val denominator = (uniqueListeners * 4) + 20f
        
        return (numerator / denominator).coerceIn(0f, 1f)
    }

    /**
     * Calculates a Human Integrity Factor [0.5 - 1.0] based on community consensus.
     * Rewards content that resonates with mature and independent listeners.
     * Uses a conservative smoothing to prevent small-sample bias.
     */
    fun calculateHumanIntegrityFactor(): Float {
        if (uniqueListeners == 0L) return 1.0f // Baseline for new content
        
        // Bayesian smoothing for ratios (K=2 prior listeners)
        val matureRatio = (matureListeners + 1f) / (uniqueListeners + 2f)
        val independentRatio = (independentListeners + 1f) / (uniqueListeners + 2f)
        
        // Mean integrity factor. 
        // 0.5 prior means it starts neutral and moves towards 1.0 with positive evidence.
        return ((matureRatio + independentRatio) / 2f).coerceIn(0.5f, 1.0f)
    }
}
