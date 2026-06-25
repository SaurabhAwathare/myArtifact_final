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
     * Enforces explicit, non-ordinal transitions according to the
     * [Publishing Flow Invariants](file:///docs/architecture/PublishingFlowInvariants.md).
     *
     * Transitions are allowed only if defined in the transition matrix,
     * or if [isRecovery] is true.
     */
    fun canTransitionTo(next: ArtifactLifecycle, isRecovery: Boolean = false): Boolean {
        if (isRecovery) return true
        if (this == next) return true
        
        val allowed = transitions[this] ?: emptySet()
        return next in allowed
    }

    companion object {
        private val transitions: Map<ArtifactLifecycle, Set<ArtifactLifecycle>> = mapOf(
            RECORDING to setOf(PROCESSING),
            PROCESSING to setOf(REVIEW_REQUIRED),
            REVIEW_REQUIRED to setOf(METADATA_REQUIRED),
            METADATA_REQUIRED to setOf(READY_TO_PUBLISH),
            READY_TO_PUBLISH to setOf(PUBLISHED),
            PUBLISHED to setOf(DELETING),
            DELETING to setOf(DELETED),
            DELETED to emptySet()
        )
    }
}
