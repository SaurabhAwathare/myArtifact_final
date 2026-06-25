package com.saurabh.artifact.util

/**
 * Feature flags for the Artifact Player Refactor.
 * Allows for incremental rollout and safe rollback of architectural changes.
 */
object RefactorFeatureFlags {
    /**
     * When true, Repositories will write to Intent-based collections 
     * and rely on Cloud Functions for global state updates.
     */
    const val USE_SERVER_AUTHORITY = true

    /**
     * When true, engagement data will be synced as raw evidence only.
     */
    const val USE_SERVER_ENGAGEMENT_VALIDATION = false

    /**
     * When true, the new unified interaction queue and worker will be used.
     */
    const val USE_UNIFIED_INTERACTION_QUEUE = true
}
