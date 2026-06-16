package com.saurabh.artifact.model

import kotlinx.serialization.Serializable

/**
 * Represents the high-level, human-facing intent and ownership of an artifact.
 */
@Serializable
enum class ArtifactLifecycle {
    /** Actively being recorded by the user. */
    RECORDING,
    
    /** Recorded but requires local processing (transcoding, etc.). */
    PROCESSING,
    
    /** Local processing complete, waiting for user review. */
    REVIEW_REQUIRED,

    /** User has reviewed, now requires metadata (title, emotion). */
    METADATA_REQUIRED,
    
    /** User has reviewed and added metadata, ready for final approval. */
    READY_TO_PUBLISH,
    
    /** Live on the network and visible to others. */
    PUBLISHED,
    
    /** Marked for deletion but not yet purged from storage. */
    DELETED,

    /** Actively being purged from storage and database. */
    DELETING;

    /**
     * Enforces forward-only progression of the lifecycle.
     * Transitions are allowed if the [next] state has a higher or equal ordinal,
     * or if [isRecovery] is true.
     */
    fun canTransitionTo(next: ArtifactLifecycle, isRecovery: Boolean = false): Boolean {
        if (isRecovery) return true
        return next.ordinal >= this.ordinal
    }
}
