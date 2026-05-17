package com.saurabh.artifact.presentation.publish

import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.model.EmotionalRiskAssessment
import com.saurabh.artifact.model.FlaggedSegment

data class EmotionalConfirmationUiState(
    val draftId: String = "",
    val currentState: ArtifactDraftState = ArtifactDraftState.PROCESSING,
    val ritualState: PublishRitualState = PublishRitualState.Idle,
    val transcript: String? = null,
    val flaggedSegments: List<FlaggedSegment> = emptyList(),
    val holdToPublishProgress: Float = 0f,
    val riskAssessment: EmotionalRiskAssessment? = null,
    val isCooldownActive: Boolean = false,
    val cooldownRemainingSeconds: Long = 0,
    val publishConfidence: Float = 0.5f,
    val showSensitiveInfoWarning: Boolean = false,
    val isProcessing: Boolean = false,
    val error: String? = null
)
