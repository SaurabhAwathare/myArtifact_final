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
    
    /** User has reviewed/approved, ready for manual or automatic publishing. */
    READY_TO_PUBLISH,
    
    /** Live on the network and visible to others. */
    PUBLISHED,
    
    /** Marked for deletion but not yet purged from storage. */
    DELETED
}
