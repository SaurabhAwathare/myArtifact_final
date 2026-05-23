package com.saurabh.artifact.audio

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.saurabh.artifact.model.Artifact
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlayer @Inject constructor(
    private val playbackSessionManager: PlaybackSessionManager
) {
    val currentArtifact = playbackSessionManager.currentArtifact
    val isPlaying = playbackSessionManager.isPlaying
    val isBuffering = playbackSessionManager.isBuffering
    val currentPosition = playbackSessionManager.currentPosition
    val duration = playbackSessionManager.duration
    val playbackSpeed = playbackSessionManager.playbackSpeed
    val error = playbackSessionManager.error

    // Keep the flows for backward compatibility if needed, though they now delegate
    private val _onPlaybackCompleted = MutableSharedFlow<String>(replay = 0)
    val onPlaybackCompleted: SharedFlow<String> = _onPlaybackCompleted.asSharedFlow()

    fun play(artifact: Artifact, initialPosition: Long = 0L) {
        playbackSessionManager.play(
            artifact = artifact, 
            owner = PlaybackSessionManager.InteractionOwner.PUBLIC_PLAYER,
            initialPosition = initialPosition
        )
    }

    fun togglePlayPause() {
        playbackSessionManager.togglePlayPause()
    }

    fun seekTo(position: Long) {
        playbackSessionManager.seekTo(position)
    }

    fun stop() {
        playbackSessionManager.stop()
    }

    fun release() {
        playbackSessionManager.release()
    }
}
