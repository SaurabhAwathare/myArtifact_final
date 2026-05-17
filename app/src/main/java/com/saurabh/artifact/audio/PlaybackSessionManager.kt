package com.saurabh.artifact.audio

import android.util.Log
import com.saurabh.artifact.model.Artifact
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level manager for coordinating playback sessions across the app.
 * Provides a single source of truth for the currently active audio.
 */
@Singleton
class PlaybackSessionManager @Inject constructor(
    private val audioPlayer: AudioPlayer
) {
    val currentArtifact: StateFlow<Artifact?> = audioPlayer.currentArtifact
    val isPlaying: StateFlow<Boolean> = audioPlayer.isPlaying
    val isBuffering: StateFlow<Boolean> = audioPlayer.isBuffering
    val currentPosition: StateFlow<Long> = audioPlayer.currentPosition
    val duration: StateFlow<Long> = audioPlayer.duration

    /**
     * Initiates playback for an artifact with safety checks.
     */
    fun playArtifact(artifact: Artifact) {
        if (artifact.audioUrl.isBlank()) {
            Log.e("PlaybackSession", "Silent Failure: audioUrl is blank for artifact ${artifact.id}")
            return
        }

        if (!artifact.audioUrl.startsWith("http") && !artifact.audioUrl.startsWith("/")) {
            Log.e("PlaybackSession", "Silent Failure: Invalid URI scheme for artifact ${artifact.id}: ${artifact.audioUrl}")
            return
        }

        Log.d("PlaybackSession", "Initiating playback for: ${artifact.title} (${artifact.audioUrl})")
        audioPlayer.play(artifact)
    }

    fun togglePlayback() {
        audioPlayer.togglePlayPause()
    }

    fun seekTo(position: Long) {
        audioPlayer.seekTo(position)
    }

    fun stop() {
        audioPlayer.stop()
    }
}
