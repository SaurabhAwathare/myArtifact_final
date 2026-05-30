package com.saurabh.artifact.model

/**
 * Legacy upload status enum.
 */
enum class UploadStatus {
    IDLE,
    QUEUED,
    UPLOADING,
    COMPLETED,
    FAILED
}
