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
class AudioPlayer @Inject constructor(@ApplicationContext private val context: Context) {
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var positionUpdateJob: Job? = null
    
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controllerLock = kotlinx.coroutines.sync.Mutex()

    private val _currentArtifact = MutableStateFlow<Artifact?>(null)
    val currentArtifact: StateFlow<Artifact?> = _currentArtifact.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _onPlaybackCompleted = MutableSharedFlow<String>(replay = 0)
    val onPlaybackCompleted: SharedFlow<String> = _onPlaybackCompleted.asSharedFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _isSkipSilenceEnabled = MutableStateFlow(false)
    val isSkipSilenceEnabled: StateFlow<Boolean> = _isSkipSilenceEnabled.asStateFlow()

    private val _currentUrl = MutableStateFlow<String?>(null)

    private val _error = MutableSharedFlow<String>(replay = 0)
    val error: SharedFlow<String> = _error.asSharedFlow()

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val diagnosticInfo = "Player error [Code: ${error.errorCode}]: ${error.message}"
            android.util.Log.e("AudioPlayer", diagnosticInfo, error)
            
            // User-friendly error message mapping
            val userMessage = when (error.errorCode) {
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> {
                    "Unable to load audio. Please check your connection."
                }
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED -> {
                    "This artifact's audio format is not supported on this device."
                }
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> {
                    "This artifact couldn't be played right now. (Invalid format)"
                }
                else -> "An unexpected playback error occurred."
            }
            
            // Critical Diagnostic for Silent Failures
            if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                android.util.Log.e("AudioPlayer", "Access Denied or Not Found: Check Firebase Storage Rules and tokenized URL.")
            } else if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
                android.util.Log.e("AudioPlayer", "Hardware Decoder Failure: Incompatible audio codec/profile.")
            }

            _isPlaying.value = false
            _isBuffering.value = false
            scope.launch {
                _error.emit(userMessage)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) {
                _duration.value = controller?.duration?.coerceAtLeast(0) ?: 0
                startPositionUpdates()
            } else {
                stopPositionUpdates()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            _isBuffering.value = state == Player.STATE_BUFFERING
            
            if (state == Player.STATE_READY) {
                _duration.value = controller?.duration?.coerceAtLeast(0) ?: 0
            }
            
            if (state == Player.STATE_ENDED) {
                android.util.Log.d("ReviewDebug", "Playback ended")
                android.util.Log.d("ReviewDebug", "FINAL duration=${controller?.duration}")

                // Force final position update to ensure completion is captured
                controller?.duration?.let { dur ->
                    if (dur > 0) _currentPosition.value = dur
                }
                _isPlaying.value = false
                stopPositionUpdates()
                
                // Emit completion event for the current URL
                val url = _currentUrl.value
                if ((url != null) && ((controller?.duration ?: 0) > 0)) {
                    scope.launch {
                        _onPlaybackCompleted.emit(url)
                    }
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _currentUrl.value = mediaItem?.mediaId
        }
    }

    init {
        // Initialization is now strictly lazy on first playback access.
    }

    private suspend fun getController(): MediaController? {
        if (controller != null) return controller
        
        return controllerLock.withLock {
            if (controller != null) return@withLock controller

            val completer = CompletableDeferred<MediaController>()
            val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, sessionToken).buildAsync()
            
            future.addListener({
                try {
                    val instance = future.get()
                    instance.addListener(playerListener)
                    completer.complete(instance)
                } catch (e: Exception) {
                    android.util.Log.e("AudioPlayer", "Failed to initialize MediaController", e)
                    completer.completeExceptionally(e)
                }
            }, MoreExecutors.directExecutor())
            
            try {
                controller = completer.await()
                controllerFuture = future
                
                // Sync initial state
                _isPlaying.value = controller?.isPlaying ?: false
                _duration.value = controller?.duration?.coerceAtLeast(0) ?: 0
                if (controller?.isPlaying == true) startPositionUpdates()
                
                controller
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Pre-warming the media controller is no longer encouraged during startup
     * to prevent main-thread contention. Use carefully.
     */
    suspend fun preWarm() {
        if (controller == null) {
            getController()
        }
    }

    fun play(artifact: Artifact, initialPosition: Long = 0L) {
        scope.launch {
            val player = getController() ?: return@launch
            playInternal(player, artifact.audioUrl, artifact.title, artifact.username, artifact, initialPosition)
        }
    }

    fun play(url: String, title: String? = null, initialPosition: Long = 0L) {
        scope.launch {
            val player = getController() ?: return@launch
            playInternal(player, url, title, null, null, initialPosition)
        }
    }

    private fun playInternal(player: MediaController, url: String, title: String?, artist: String?, artifact: Artifact?, initialPosition: Long = 0L) {
        if (url.isBlank()) return
        
        if (url.startsWith("/")) {
            val file = File(url)
            if (!file.exists() || (file.length() == 0L)) {
                android.util.Log.e("AudioPlayer", "Playback failed: File missing or empty at $url")
                return
            }
        }

        if (_currentUrl.value == url) {
            if (!player.isPlaying) {
                player.play()
            }
            return
        }

        _currentArtifact.value = artifact
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaId(url)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title ?: "Audio Content")
                    .setArtist(artist ?: "Artifact")
                    .build()
            )
            .build()
        
        player.setMediaItem(mediaItem)
        player.setPlaybackSpeed(_playbackSpeed.value)
        if (initialPosition > 0) {
            player.seekTo(initialPosition)
        }
        player.prepare()
        player.play()
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        scope.launch {
            getController()?.setPlaybackSpeed(speed)
        }
    }

    fun setSkipSilenceEnabled(enabled: Boolean) {
        _isSkipSilenceEnabled.value = enabled
        // Using custom command since MediaController doesn't have setSkipSilenceEnabled
        scope.launch {
            getController()?.sendCustomCommand(
                androidx.media3.session.SessionCommand("SET_SKIP_SILENCE", android.os.Bundle().apply {
                    putBoolean("enabled", enabled)
                }),
                android.os.Bundle.EMPTY
            )
        }
    }

    fun togglePlayPause() {
        scope.launch {
            val player = getController() ?: return@launch
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }
                player.play()
            }
        }
    }

    fun seekTo(position: Long) {
        scope.launch {
            getController()?.seekTo(position)
            _currentPosition.value = position
        }
    }

    fun stop() {
        scope.launch {
            getController()?.stop()
            _isPlaying.value = false
            _currentUrl.value = null
            _currentArtifact.value = null
            stopPositionUpdates()
        }
    }

    private fun startPositionUpdates() {
        val currentController = controller ?: return // Guard: Don't start updates if controller hasn't been lazily initialized
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (isActive) {
                if (_isPlaying.value) { // Only update if actually playing
                    _currentPosition.value = currentController.currentPosition
                }
                delay(200)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
    }

    fun release() {
        controller?.removeListener(playerListener)
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        controller = null
        controllerFuture = null
        scope.cancel()
    }
}
