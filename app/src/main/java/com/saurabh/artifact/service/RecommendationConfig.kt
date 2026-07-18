package com.saurabh.artifact.service

/**
 * Authoritative configuration for the Artifact Recommendation Engine.
 * 
 * Exposing these values here allows the engine to remain stateless and makes
 * it easier to experiment with different weights and windows without modifying
 * the core logic.
 */
data class RecommendationConfig(
    /**
     * Percentage of the feed reserved for "under-heard" voices (low play counts).
     */
    val explorationRatio: Float = 0.15f,

    /**
     * Window in hours for an artifact to be considered "Fresh".
     */
    val freshnessWindowHours: Int = 24,

    /**
     * Window in days for an artifact to be considered "Standard".
     * Artifacts older than this are considered "Evergreen".
     */
    val standardWindowDays: Int = 7,

    /**
     * Maximum number of consecutive artifacts from the same creator.
     */
    val maxConsecutiveFromCreator: Int = 1,

    /**
     * The target size of the candidate pool fetched from the repository
     * before ranking and filtering.
     */
    val candidatePoolSize: Int = 75,

    /**
     * Play count threshold below which an artifact is considered "under-heard".
     */
    val underHeardThreshold: Long = 5,
    
    /**
     * Weight for recency in the internal sorting.
     */
    val recencyWeight: Float = 1.0f,
    
    /**
     * Weight for resonance (reactionCount) in the internal sorting.
     */
    val resonanceWeight: Float = 0.5f
) {
    companion object {
        val DEFAULT = RecommendationConfig()
    }
}
