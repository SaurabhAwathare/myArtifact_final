package com.saurabh.artifact.data.local

/**
 * Represents the outcome of an attempt to acquire ownership of an upload task.
 * Used to distinguish between a successful acquisition, a conflict, or a missing task.
 */
enum class AcquisitionResult {
    /** Ownership successfully acquired or already held by the caller. */
    ACQUIRED,
    
    /** Ownership is held by another component (e.g., Service vs Worker). */
    LOCKED,
    
    /** The upload task does not exist (likely already completed and deleted). */
    MISSING
}
