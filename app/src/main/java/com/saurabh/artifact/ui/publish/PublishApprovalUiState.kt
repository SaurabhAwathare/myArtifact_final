package com.saurabh.artifact.ui.publish

import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.model.TranscriptSegment

data class PublishApprovalUiState(
    val draftId: String = "",
    val title: String = "",
    val description: String = "",
    val emotion: String = "",
    val tags: List<String> = emptyList(),
    val transcript: List<TranscriptSegment> = emptyList(),
    val audioDurationMs: Long = 0,
    val isPublic: Boolean = true,
    val isListened: Boolean = false,
    
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null,
    
    val hasSensitiveInfo: Boolean = false,
    val isHighRisk: Boolean = false,
    val sensitiveFlagCount: Int = 0,
    val identityRiskScore: Float = 0f,
    
    val confirmedComfortable: Boolean = false,
    val confirmedSensitiveRemoved: Boolean = false,
    val confirmedComplete: Boolean = false,
    
    val showPrivacyNudge: Boolean = false,
    val privacyWarnings: List<String> = emptyList(),
    val isPrivacyNudgeBypassed: Boolean = false,
    
    val currentState: ArtifactDraftState = ArtifactDraftState.READY_TO_REVIEW
) {
    val canApprove: Boolean get() = title.isNotBlank() && isListened && !isLoading
}
