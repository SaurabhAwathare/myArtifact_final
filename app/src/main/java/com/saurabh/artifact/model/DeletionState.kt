package com.saurabh.artifact.model

/**
 * Represents the current state of an artifact's deletion journey.
 * Used for UI synchronization and playback coordination.
 */
sealed class DeletionState {
    object Idle : DeletionState()
    
    /**
     * The deletion has been initiated on the client.
     */
    data class Pending(val artifactId: String) : DeletionState()
    
    /**
     * The remote anchor (Firestore document) has been successfully removed.
     * Cascading cleanup is now handled by server-side functions.
     */
    data class RemoteDeleted(val artifactId: String) : DeletionState()
    
    /**
     * An error occurred during the deletion process.
     */
    data class Error(val artifactId: String, val message: String) : DeletionState()
}
