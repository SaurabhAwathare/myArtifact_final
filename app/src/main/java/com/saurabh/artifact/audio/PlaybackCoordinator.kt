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
    private val reviewSessionManager: ReviewSessionManager,
    private val transientPlayerManager: TransientPlayerManager
) {
    val currentArtifact = playbackSessionManager.currentArtifact
    val isPlaying = playbackSessionManager.isPlaying
    val isBuffering = playbackSessionManager.isBuffering
    val currentPosition = playbackSessionManager.currentPosition
    val positionSync = playbackSessionManager.positionSync
    val durationMs = playbackSessionManager.durationMs
    val playbackSpeed = playbackSessionManager.playbackSpeed
    val isSkipSilenceEnabled = playbackSessionManager.isSkipSilenceEnabled
    val playbackCompletedEvent = playbackSessionManager.playbackCompletedEvent
    val activePlayback = playbackSessionManager.activePlayback

    val isAmbientPlaying = transientPlayerManager.isPlaying

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
     * Plays an ambient sound layer simultaneously with the main audio.
     */
    fun playAmbient(url: String, loop: Boolean = true, volume: Float = 0.3f) {
        transientPlayerManager.play(url, loop, volume)
    }

    fun stopAmbient() {
        transientPlayerManager.stop()
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

    /**
     * Requests to stop playback only if it's owned by a certain interaction owner.
     */
    fun requestStop(owner: PlaybackSessionManager.InteractionOwner) {
        playbackSessionManager.stopIfOwner(owner)
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
