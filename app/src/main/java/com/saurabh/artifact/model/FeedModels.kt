package com.saurabh.artifact.model

import com.google.firebase.Timestamp
import kotlinx.serialization.Serializable

/**
 * A wrapper for Artifacts appearing in the feed, including recommendation metadata.
 */
data class FeedArtifact(
    val artifact: Artifact,
    val reason: FeedRecommendationReason = FeedRecommendationReason.DISCOVERY,
    val compatibilityScore: Float = 0f,
    val isUnfinished: Boolean = false,
    val lastPositionMs: Long = 0
)

/**
 * Reasons why an artifact was recommended to the user.
 */
enum class FeedRecommendationReason(val label: String) {
    RESONATING_PRESENCE("From a presence you resonate with"),
    EMOTIONAL_RESONANCE("Resonates with your mood"),
    SIMILAR_TOPIC("A topic you've explored"),
    CONTINUE_LISTENING("Pick up where you left off"),
    DISCOVERY("A voice you might connect with")
}

/**
 * Tracks a user's progress through an artifact.
 */
data class ListeningSession(
    val id: String = "",
    val userId: String = "",
    val artifactId: String = "",
    val lastPositionMs: Long = 0,
    val totalDurationMs: Long = 0,
    val isCompleted: Boolean = false,
    val updatedAt: Timestamp = Timestamp.now()
)

/**
 * Defines the user's current "listening mood" and preferences.
 */
data class EmotionalCompatibilityProfile(
    val userId: String = "",
    val preferredEmotions: List<String> = emptyList(),
    val intensityThreshold: Float = 0.5f, // 0.0 (Calm) to 1.0 (Intense)
    val lastUpdated: Timestamp = Timestamp.now()
)

/**
 * Represents a resonance relationship (Private by design).
 */
data class ResonanceRelation(
    val resonatingId: String,
    val resonatedId: String,
    val createdAt: Timestamp = Timestamp.now()
)

/**
 * State of the feed composition.
 */
sealed class FeedCompositionState {
    object Loading : FeedCompositionState()
    data class Success(val items: List<FeedArtifact>) : FeedCompositionState()
    data class Error(val message: String) : FeedCompositionState()
}

/**
 * Signals used for ranking.
 */
data class FeedRankingSignals(
    val relationshipStrength: Float = 0f,
    val emotionalAlignment: Float = 0f,
    val recencyBonus: Float = 0f,
    val completionProbability: Float = 0f
)
