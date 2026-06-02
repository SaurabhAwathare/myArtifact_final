package com.saurabh.artifact.model

/**
 * Formal sync state for the artifact publishing lifecycle.
 */
@Deprecated("Use DraftStatus.publication or DraftStatus.backup (SyncStatus) instead.")
enum class SyncState {
    DRAFT,            // Initial local state
    QUEUED,           // Waiting for WorkManager to pick it up
    UPLOADING,        // Active byte transfer
    RECOVERING,       // Interrupted and being reconciled by RecoveryWorker
    SYNCED,           // Fully published to Firestore and Storage
    FAILED_PERMANENT, // Terminal error (e.g. checksum mismatch, safety violation)
    LOCAL_ONLY,       // Intentionally not synced (private draft)
    STAGED            // Immutable snapshot created, ready for final firestore write
}
