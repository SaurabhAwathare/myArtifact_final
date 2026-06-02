package com.saurabh.artifact.model

/**
 * Legacy upload status enum.
 */
@Deprecated("Use DraftStatus.publication instead.")
enum class UploadStatus {
    IDLE,
    QUEUED,
    UPLOADING,
    COMPLETED,
    FAILED
}
