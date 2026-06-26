package com.saurabh.artifact.model

/**
 * Represents the synchronization state of a user interaction (Reaction, Save, Follow).
 */
enum class InteractionSyncStatus {
    /** The interaction has been successfully confirmed by the server. */
    SYNCED,

    /** The interaction is in the local queue awaiting synchronization. */
    PENDING,

    /** The interaction failed to synchronize after multiple attempts. */
    FAILED
}
