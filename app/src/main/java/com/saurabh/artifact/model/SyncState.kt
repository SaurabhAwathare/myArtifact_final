package com.saurabh.artifact.model

/**
 * SyncState - Represents the lifecycle of an artifact from creation to cloud synchronization.
 * Designed for offline-first resilience.
 */
enum class SyncState {
    /** State when the draft entity is first created but recording hasn't started or is just starting. */
    INITIALIZING,

    /** Initial state: local only, actively being recorded. */
    RECORDING,

    /** Recording was interrupted (e.g., crash, process kill). */
    INTERRUPTED,

    /** Finalized locally and ready for review or manual sync trigger. */
    STAGED,
    
    /** Finalized locally and placed in the synchronization queue. */
    QUEUED,
    
    /** Actively being uploaded to the remote storage. */
    UPLOADING,
    
    /** Successfully uploaded to cloud storage, but post-processing (e.g., transcription) might still be pending. */
    UPLOADED,
    
    /** Fully synchronized: remote and local states are identical. */
    SYNCED,
    
    /** Failed due to a permanent error (e.g., file corruption, unauthorized) and requires manual intervention. */
    FAILED_PERMANENT
}
