package com.saurabh.artifact.util

/**
 * Centralized rules for artifact sharing eligibility.
 */
object ShareEligibility {
    /**
     * Determines if an artifact is eligible for public sharing.
     */
    fun canShare(isPublic: Boolean, isDraft: Boolean): Boolean {
        return isPublic && !isDraft
    }
}
