package com.saurabh.artifact.model

import com.google.firebase.Timestamp

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
 * Sealed class representing items that can be displayed in the main feed.
 */
sealed class FeedDisplayItem {
    abstract val id: String
    
    data class ArtifactItem(
        val artifact: Artifact,
        val absoluteIndex: Int = 0
    ) : FeedDisplayItem() {
        override val id: String = artifact.id
    }
    
    data class BreakItem(override val id: String) : FeedDisplayItem()
}

/**
 * State of the feed composition.
 */
sealed class FeedCompositionState {
    object Loading : FeedCompositionState()
    data class Success(val artifacts: List<FeedArtifact>) : FeedCompositionState()
    data class Error(val message: String) : FeedCompositionState()
}
