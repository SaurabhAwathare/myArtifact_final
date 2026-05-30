package com.saurabh.artifact.audio

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.saurabh.artifact.model.Artifact
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single global owner of the playback session.
 * Responsible for:
 * - ExoPlayer/MediaController lifecycle
 * - Global playback state
 * - Ensuring only one audio source plays at a time
 */
@Singleton
class PlaybackSessionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playbackPositionDao: com.saurabh.artifact.data.local.PlaybackPositionDao,
    private val cleanupManager: dagger.Lazy<ArtifactCleanupManager>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val controllerLock = Mutex()
    
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    // Tracking which system owns the current playback session
    enum class InteractionOwner { NONE, PUBLIC_PLAYER, REVIEW_PLAYER }
    private val _interactionOwner = MutableStateFlow(InteractionOwner.NONE)
    val interactionOwner: StateFlow<InteractionOwner> = _interactionOwner.asStateFlow()
    
    private val _currentArtifact = MutableStateFlow<Artifact?>(null)
    val currentArtifact: StateFlow<Artifact?> = _currentArtifact.asStateFlow()

    private val _isPlaying = MutableStateFlow(value = false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _isSkipSilenceEnabled = MutableStateFlow(false)
    val isSkipSilenceEnabled: StateFlow<Boolean> = _isSkipSilenceEnabled.asStateFlow()

    private var positionUpdateJob: Job? = null
    private var cleanupSyncJob: Job? = null
    
    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _seekEvent = MutableSharedFlow<Long>()
    val seekEvent: SharedFlow<Long> = _seekEvent.asSharedFlow()

    private val _error = MutableSharedFlow<String>(replay = 0)
    val error: SharedFlow<String> = _error.asSharedFlow()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) startPositionUpdates() else stopPositionUpdates()
        }

        override fun onPlaybackStateChanged(state: Int) {
            _playbackState.value = state
            _isBuffering.value = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) {
                _durationMs.value = controller?.duration?.coerceAtLeast(0) ?: 0
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            scope.launch {
                _error.emit(error.message ?: "Playback error")
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Can be used to sync current artifact if needed
        }
    }

    private suspend fun getController(): MediaController? {
        if (controller != null) return controller
        
        // Start cleanup synchronization once the manager is active
        startCleanupSync()

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
                    Log.e("PlaybackSessionManager", "Failed to init MediaController", e)
                    completer.completeExceptionally(e)
                }
            }, MoreExecutors.directExecutor())
            
            try {
                controller = completer.await()
                controllerFuture = future
                _isPlaying.value = controller?.isPlaying ?: false
                _durationMs.value = controller?.duration?.coerceAtLeast(0) ?: 0
                controller
            } catch (e: Exception) {
                null
            }
        }
    }

    fun play(artifact: Artifact, owner: InteractionOwner = InteractionOwner.PUBLIC_PLAYER, initialPosition: Long = 0L) {
        scope.launch {
            val player = getController() ?: return@launch
            
            // Stop previous if any
            if (player.isPlaying) player.stop()
            
            _interactionOwner.value = owner
            _currentArtifact.value = artifact

            // Restore position if not explicitly provided
            val restoredPosition = if (initialPosition == 0L) {
                playbackPositionDao.getPosition(artifact.id)?.positionMs ?: 0L
            } else {
                initialPosition
            }

            val mediaItem = MediaItem.Builder()
                .setUri(artifact.audioUrl)
                .setMediaId(artifact.id)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(artifact.title)
                        .setArtist(artifact.author.name)
                        .build()
                )
                .build()
            
            player.setMediaItem(mediaItem)
            player.setPlaybackSpeed(_playbackSpeed.value)
            if (restoredPosition > 0) player.seekTo(restoredPosition)
            player.prepare()
            player.play()
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        scope.launch {
            getController()?.setPlaybackSpeed(speed)
        }
    }

    fun setSkipSilenceEnabled(enabled: Boolean) {
        _isSkipSilenceEnabled.value = enabled
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
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    fun seekTo(position: Long) {
        scope.launch {
            getController()?.seekTo(position)
            _currentPosition.value = position
            _seekEvent.emit(position)
        }
    }

    fun stop() {
        scope.launch {
            getController()?.stop()
            _interactionOwner.value = InteractionOwner.NONE
            _currentArtifact.value = null
            _isPlaying.value = false
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            var tick = 0
            while (isActive) {
                controller?.let { p ->
                    val pos = p.currentPosition
                    val dur = p.duration.coerceAtLeast(0)
                    _currentPosition.value = pos
                    _durationMs.value = dur

                    // Persist position periodically (every 5 seconds)
                    if ((tick % 25) == 0) {
                        _currentArtifact.value?.let { artifact ->
                            if (!artifact.isDraft) { // Drafts have their own persistence in ReviewSessionManager
                                playbackPositionDao.savePosition(
                                    com.saurabh.artifact.data.local.PlaybackPosition(
                                        artifactId = artifact.id,
                                        positionMs = pos
                                    )
                                )
                            }
                        }
                    }
                }
                delay(200)
                tick++
            }
        }
    }

    private fun startCleanupSync() {
        if (cleanupSyncJob?.isActive == true) return
        cleanupSyncJob = scope.launch {
            cleanupManager.get().deletingArtifactIds.collect { deletingIds ->
                val currentId = _currentArtifact.value?.id ?: return@collect
                if (currentId in deletingIds) {
                    Log.d("PlaybackSessionManager", "Stopping playback for $currentId as it is being deleted.")
                    stop()
                }
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
    }

    fun release() {
        cleanupSyncJob?.cancel()
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        scope.cancel()
    }
}
