package com.saurabh.artifact.ui.player

import androidx.compose.runtime.Immutable

/**
 * Isolated UI state for the review-specific interactions in the player.
 * Narrowing this state prevents unnecessary recompositions during high-frequency playback updates.
 */
@Immutable
data class ReviewInteractionUiState(
    val coveragePercent: Float = 0f,
    val isThresholdMet: Boolean = false,
    val isPlaybackEnded: Boolean = false,
    val requiredCoverage: Float = 0.95f,
    val isReachedEndRequired: Boolean = true
)
