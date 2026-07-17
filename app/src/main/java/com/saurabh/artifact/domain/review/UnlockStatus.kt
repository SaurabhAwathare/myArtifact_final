package com.saurabh.artifact.domain.review

/**
 * Authoritative unlock information provided by the backend.
 */
data class UnlockStatus(
    val isCommentUnlocked: Boolean = false,
    val unlockTimestamp: Long? = null,
    val engagementState: EngagementState = EngagementState.LOCKED,
    val unlockReason: String? = null,
    val updatedAt: Long? = null
)
