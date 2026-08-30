package com.saurabh.artifact.domain

import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.ModerationStatus
import com.saurabh.artifact.model.RecommendationState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authoritative safety policy for the Artifact platform.
 * Harmonizes safety decisions across discovery (feeds) and direct access (playback).
 */
@Singleton
class SafetyPolicy @Inject constructor() {

    /**
     * Determines if an artifact is eligible for discovery in recommendation feeds.
     * Enforces strict safety thresholds and user-specific suppression.
     */
    fun isEligibleForDiscovery(
        artifact: Artifact,
        currentUserId: String?,
        isSuppressedByUser: Boolean = false,
        ignoredUserIds: Set<String> = emptySet(),
    ): Boolean {
        // 0. Ignore List Suppression
        if (ignoredUserIds.contains(artifact.userId)) return false

        // 1. User-Specific Suppression (Reporter-side hiding)
        if (isSuppressedByUser) return false
        
        // 2. Global Safety Thresholds (Algorithmic removal)
        if (artifact.recommendationState == RecommendationState.SUPPRESSED) return false
        
        // Safety Invariant: 3 unique reports or 3 safety concerns trigger suppression
        if (artifact.reportCount >= 3L) return false
        if (artifact.safetyConcernCount >= 3L) return false

        // 3. Global Moderation State
        if (artifact.moderation.status == ModerationStatus.HIDDEN) return false

        // 4. Content Integrity
        if (artifact.status != ArtifactStatus.ACTIVE) return false
        if (artifact.audioUrl.isEmpty()) return false

        return true
    }

    /**
     * Determines if an artifact is eligible for intentional direct access (e.g. via deep link).
     * SUPPRESSED artifacts remain playable if they haven't been globally HIDDEN or DELETED.
     */
    fun isEligibleForPlayback(artifact: Artifact): Boolean {
        // Playback Invariant: Block only if DELETED or globally HIDDEN
        if (artifact.status == ArtifactStatus.DELETED) return false
        if (artifact.moderation.status == ModerationStatus.HIDDEN) return false

        return true
    }
}
