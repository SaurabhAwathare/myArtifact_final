package com.saurabh.artifact.ui.publish

import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.model.TranscriptSegment

data class PublishApprovalUiState(
    val isLoading: Boolean = false,
    val draftId: String = "",
    val title: String = "",
    val emotion: String = "",
    val tags: List<String> = emptyList(),
    val transcript: List<TranscriptSegment> = emptyList(),
    val audioDurationMs: Long = 0,
    val isPublic: Boolean = true,
    
    // Validation & Warnings
    val hasSensitiveInfo: Boolean = false,
    val isHighRisk: Boolean = false,
    val sensitiveFlagCount: Int = 0,
    
    // Checklist
    val confirmedComfortable: Boolean = false,
    val confirmedSensitiveRemoved: Boolean = false,
    val confirmedComplete: Boolean = false,
    
    // Process State
    val currentState: ArtifactDraftState = ArtifactDraftState.PENDING_APPROVAL,
    val error: String? = null,
    val isSuccess: Boolean = false
) {
    val canApprove: Boolean
        get() = title.isNotBlank() && !isLoading
}
