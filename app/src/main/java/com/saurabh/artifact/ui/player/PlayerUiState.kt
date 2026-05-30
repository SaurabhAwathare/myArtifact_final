package com.saurabh.artifact.ui.player

import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.model.ReactionType

/**
 * Represents the comprehensive UI state for the immersive audio player.
 */
data class PlayerUiState(
    val currentArtifact: Artifact? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPosition: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val isCommentUnlocked: Boolean = false,
    val listeningProgress: Float = 0f, // 0.0 to 1.0
    val error: String? = null,
    val isExpanded: Boolean = false,
    val playerMode: PlayerMode = PlayerMode.HIDDEN,

    // Interaction State
    val isResonated: Boolean = false,
    val selectedReactionType: ReactionType = ReactionType.I_HEAR_YOU,
    val isFollowed: Boolean = false,
    val isSaved: Boolean = false,
    val resonanceSummary: String = "",
    val commentCount: Int = 0,
    
    // Advanced Controls State
    val isSilenceSkipEnabled: Boolean = false,
    val sleepTimerMillisRemaining: Long? = null,
    val currentTranscriptSegment: TranscriptSegment? = null,
    val showAdvancedControls: Boolean = false
)

enum class PlayerMode {
    HIDDEN,
    MINI,
    FULLSCREEN
}

/**
 * Metadata specifically for the audio track, used for waveform and display.
 */
data class ArtifactAudioMetadata(
    val id: String,
    val title: String,
    val authorName: String,
    val authorAvatarUrl: String?,
    val waveformData: List<Float> = emptyList(),
    val durationMillis: Long = 0L
)
