package com.saurabh.artifact.model

/**
 * Legacy sync state enum.
 */
enum class SyncState {
    DRAFT,
    INTERRUPTED,
    SYNCED,
    LOCAL_ONLY,
    STAGED,
    FAILED_PERMANENT,
    UPLOADING
}
