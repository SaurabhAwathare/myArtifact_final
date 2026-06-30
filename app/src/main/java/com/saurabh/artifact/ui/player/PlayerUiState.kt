package com.saurabh.artifact.ui.player

import com.saurabh.artifact.model.*

/**
 * Represents the comprehensive UI state for the immersive audio player.
 */
data class PlayerUiState(
    val currentArtifact: Artifact? = null,
    val currentPlayableArtifact: PlayableArtifact? = null,
    val loadState: PlayerLoadState = PlayerLoadState.IDLE,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPosition: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val playbackProgress: Float = 0f, // Actual current position (0.0 to 1.0)
    val listeningProgress: Float = 0f, // Furthest point reached (0.0 to 1.0)
    val engagementStatus: EngagementStatus = EngagementStatus.LOCKED,
    val error: String? = null,
    val isExpanded: Boolean = false,
    val playerMode: PlayerMode = PlayerMode.HIDDEN,

    // Interaction State
    val isResonated: Boolean = false,
    val resonanceSyncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED,
    val selectedReactionType: ReactionType = ReactionType.I_HEAR_YOU,
    val isResonating: Boolean = false,
    val followSyncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED,
    val isSaved: Boolean = false,
    val saveSyncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED,
    val isOwner: Boolean = false,
    val resonanceSummary: String = "",
    val commentCount: Long = 0,
    
    // Advanced Controls State
    val isSilenceSkipEnabled: Boolean = false,
    val sleepTimerMillisRemaining: Long? = null,
    val currentTranscriptSegment: TranscriptSegment? = null,
    val showAdvancedControls: Boolean = false,
    val showComments: Boolean = false,

    // Review Mode State (Phase 1)
    val coveragePercent: Float = 0f,
    val isThresholdMet: Boolean = false,
    val isPlaybackEnded: Boolean = false,
    
    // Unlock Requirements (Policy-driven)
    val requiredCoverage: Float = 0.95f,
    val isReachedEndRequired: Boolean = true
)

enum class PlayerLoadState {
    IDLE,
    LOADING,
    LOADED,
    ERROR
}

enum class PlayerMode {
    HIDDEN,
    MINI,
    FULLSCREEN
}
