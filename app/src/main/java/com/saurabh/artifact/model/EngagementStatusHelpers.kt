package com.saurabh.artifact.model

/**
 * Returns true if the artifact's engagement status allows commenting.
 * Unlocked or pending validation both allow commenting for a consistent UX.
 */
fun EngagementStatus.isCommentAvailable(): Boolean {
    return this == EngagementStatus.UNLOCKED || this == EngagementStatus.VERIFYING
}
