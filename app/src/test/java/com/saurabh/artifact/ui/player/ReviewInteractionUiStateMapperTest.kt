package com.saurabh.artifact.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewInteractionUiStateMapperTest {

    @Test
    fun `mapping from PlayerUiState to ReviewInteractionUiState is correct`() {
        val uiState = PlayerUiState(
            coveragePercent = 0.5f,
            isThresholdMet = false,
            isPlaybackEnded = false,
            requiredCoverage = 0.95f,
            isReachedEndRequired = true
        )

        val reviewState = ReviewInteractionUiState(
            coveragePercent = uiState.coveragePercent,
            isThresholdMet = uiState.isThresholdMet,
            isPlaybackEnded = uiState.isPlaybackEnded,
            requiredCoverage = uiState.requiredCoverage,
            isReachedEndRequired = uiState.isReachedEndRequired
        )

        assertEquals(0.5f, reviewState.coveragePercent)
        assertEquals(false, reviewState.isThresholdMet)
        assertEquals(false, reviewState.isPlaybackEnded)
        assertEquals(0.95f, reviewState.requiredCoverage)
    }

    @Test
    fun `isThresholdMet correctly reflects change in source state`() {
        val uiState = PlayerUiState(
            coveragePercent = 0.96f,
            isThresholdMet = true
        )

        val reviewState = ReviewInteractionUiState(
            coveragePercent = uiState.coveragePercent,
            isThresholdMet = uiState.isThresholdMet
        )

        assertEquals(true, reviewState.isThresholdMet)
    }
}
