package com.saurabh.artifact.model

/**
 * Legacy upload status enum.
 */
@Deprecated("Use DraftStatus.sync instead.")
enum class UploadStatus {
    IDLE,
    QUEUED,
    UPLOADING,
    COMPLETED,
    FAILED
}
