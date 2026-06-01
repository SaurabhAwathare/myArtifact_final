package com.saurabh.artifact.audio

import com.saurabh.artifact.model.Artifact
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level coordinator for all playback types.
 * Manages the "why" and "when" of playback, while [PlaybackSessionManager] handles the "how".
 */
@Singleton
class PlaybackCoordinator @Inject constructor(
    private val playbackSessionManager: PlaybackSessionManager,
    private val reviewSessionManager: ReviewSessionManager
) {
    val currentArtifact = playbackSessionManager.currentArtifact
    val isPlaying = playbackSessionManager.isPlaying
    val isBuffering = playbackSessionManager.isBuffering
    val currentPosition = playbackSessionManager.currentPosition
    val durationMs = playbackSessionManager.durationMs
    val playbackSpeed = playbackSessionManager.playbackSpeed
    val isSkipSilenceEnabled = playbackSessionManager.isSkipSilenceEnabled
    val playbackCompletedEvent = playbackSessionManager.playbackCompletedEvent
    val activePlayback = playbackSessionManager.activePlayback

    /**
     * Start playing a persistent artifact (e.g., from the main feed).
     */
    fun playArtifact(artifact: Artifact, collection: List<Artifact> = emptyList(), initialPosition: Long = 0L) {
        playbackSessionManager.play(
            artifact = artifact,
            collection = collection,
            owner = PlaybackSessionManager.InteractionOwner.PUBLIC_PLAYER,
            playbackType = PlaybackType.ARTIFACT,
            initialPosition = initialPosition
        )
    }

    /**
     * Start a draft preview. These are typically transient and might be stopped 
     * by the coordinator when the user leaves the draft edit screen.
     */
    fun playDraftPreview(draftId: String) {
        reviewSessionManager.startReview(draftId)
    }

    /**
     * Start a profile preview.
     */
    fun playProfilePreview(artifact: Artifact) {
        playbackSessionManager.play(
            artifact = artifact,
            owner = PlaybackSessionManager.InteractionOwner.PUBLIC_PLAYER,
            playbackType = PlaybackType.PROFILE_PREVIEW
        )
    }

    fun togglePlayPause() {
        playbackSessionManager.togglePlayPause()
    }

    fun stop() {
        playbackSessionManager.stop()
    }

    /**
     * Requests to stop playback only if it's of a certain type.
     * Useful for ViewModels to call in onCleared() without stopping persistent playback.
     */
    fun requestStop(type: PlaybackType) {
        playbackSessionManager.stopIfType(type)
    }

    fun seekTo(position: Long) {
        playbackSessionManager.seekTo(position)
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackSessionManager.setPlaybackSpeed(speed)
    }

    fun setSkipSilenceEnabled(enabled: Boolean) {
        playbackSessionManager.setSkipSilenceEnabled(enabled)
    }

    fun preCache(artifact: Artifact) {
        playbackSessionManager.preCache(artifact)
    }
}
