package com.saurabh.artifact.service

import com.saurabh.artifact.model.ArtifactConversationMetadata
import com.saurabh.artifact.model.ConversationStyle
import com.saurabh.artifact.model.StyleModerationState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Evaluates the safety and emotional volatility of conversation style combinations.
 */
@Singleton
class StyleModerationService @Inject constructor() {

    /**
     * Evaluates metadata and returns a safety state.
     * Prevents "Rant Escalation" and handles "Vulnerability-Sensitive" distribution.
     */
    fun evaluateSafety(metadata: ArtifactConversationMetadata): StyleModerationState {
        val primary = metadata.primaryStyle ?: return StyleModerationState.SAFE
        val secondary = metadata.secondaryStyles

        // 1. Rant Escalation: High energy venting + chaotic energy
        if (primary == ConversationStyle.RANT && secondary.contains(ConversationStyle.CHAOTIC)) {
            return StyleModerationState.SENSITIVE
        }

        // 2. High Vulnerability combinations
        if (primary == ConversationStyle.COMFORT && secondary.contains(ConversationStyle.REFLECTIVE)) {
            // Comforting/Reflective is safe but might need "soft distribution"
            // (e.g., don't show to people in a high-energy mood)
            return StyleModerationState.SAFE 
        }

        // 3. Volatile combinations (Example: Rant + Storytelling about sensitive topics)
        // This would ideally integrate with TopicModerationState in a real app
        
        return StyleModerationState.SAFE
    }

    /**
     * Suggests distribution strategy based on style safety.
     */
    fun getDistributionStrategy(state: StyleModerationState): DistributionStrategy {
        return when (state) {
            StyleModerationState.SAFE -> DistributionStrategy.WIDE
            StyleModerationState.SENSITIVE -> DistributionStrategy.FOCUSED // Only to compatible listeners
            StyleModerationState.VOLATILE -> DistributionStrategy.REVIEW_REQUIRED
            StyleModerationState.RESTRICTED -> DistributionStrategy.NONE
        }
    }
}

enum class DistributionStrategy {
    WIDE,           // Standard feed ranking
    FOCUSED,        // Compatible affinity only
    REVIEW_REQUIRED,// Human-in-the-loop
    NONE            // Blocked
}
