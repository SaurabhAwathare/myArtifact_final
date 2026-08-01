package com.saurabh.artifact.ui.comment

/**
 * UI-specific state for the comment unlock mechanism.
 * Derived from backend authority and local sync status.
 */
enum class CommentUnlockState {
    /** User must listen to the artifact to unlock. */
    LOCKED,
    
    /** Local evidence is being uploaded to the server. */
    SYNCING,
    
    /** Upload complete, waiting for backend authoritative validation. */
    VERIFYING,
    
    /** Commenting is allowed. */
    UNLOCKED,
    
    /** Synchronization or validation failed. */
    ERROR,

    /** Backend validation timed out. */
    TIMEOUT
}
