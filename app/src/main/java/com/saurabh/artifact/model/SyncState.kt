package com.saurabh.artifact.model

/**
 * Represents the synchronization state of a local entity with the remote server.
 */
enum class SyncState {
    /** Awaiting synchronization. */
    PENDING,

    /** Currently being uploaded. */
    SYNCING,

    /** Successfully synchronized. */
    SYNCED,

    /** Failed to synchronize after attempts. */
    FAILED
}
