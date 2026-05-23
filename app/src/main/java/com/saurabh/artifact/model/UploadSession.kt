package com.saurabh.artifact.model

/**
 * State container for an active or recently completed upload session.
 */
data class UploadSession(
    val draftId: String,
    val title: String,
    val status: AmbientUploadStatus,
    val progress: Float = 0f
)
