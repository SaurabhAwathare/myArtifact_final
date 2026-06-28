package com.saurabh.artifact.model

/**
 * Domain-level representation of the engagement/unlock state for an artifact.
 * UI-agnostic to maintain separation of concerns.
 */
enum class EngagementStatus {
    /** The user has not met the immersion threshold yet. */
    LOCKED,

    /** The user has met the threshold locally; synchronization with the server is in progress. */
    VERIFYING,

    /** The server has authoritatively confirmed that the artifact is unlocked for this user. */
    UNLOCKED,

    /** 
     * A permanent failure occurred during synchronization. 
     * Note: Transient failures (retries) should generally remain in VERIFYING.
     */
    ERROR
}
