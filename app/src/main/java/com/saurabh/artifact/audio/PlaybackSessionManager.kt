package com.saurabh.artifact.audio

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.repository.EngagementRepository
import dagger.Lazy
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
 * - Queue management and persistence
 */
@Singleton
class PlaybackSessionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val engagementRepository: EngagementRepository,
    private val cleanupManager: Lazy<ArtifactCleanupManager>,
    private val settingsDataStore: PlaybackSettingsDataStore,
    private val analytics: PlaybackAnalyticsManager,
    private val artifactRepository: Lazy<com.saurabh.artifact.repository.ArtifactRepository>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val controllerLock = Mutex()
    
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    // Tracking which system owns the current playback session
    enum class InteractionOwner { NONE, PUBLIC_PLAYER, REVIEW_PLAYER, SERVICE }
    private val _interactionOwner = MutableStateFlow(InteractionOwner.NONE)
    val interactionOwner: StateFlow<InteractionOwner> = _interactionOwner.asStateFlow()
    
    private val _activePlayback = MutableStateFlow<ActivePlayback?>(null)
    val activePlayback: StateFlow<ActivePlayback?> = _activePlayback.asStateFlow()
    
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
    
    private var errorRetryCount = 0
    private val MAX_RETRIES = 3

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _playbackCompletedEvent = MutableSharedFlow<String>(replay = 0)
    val playbackCompletedEvent: SharedFlow<String> = _playbackCompletedEvent.asSharedFlow()

    private val _seekEvent = MutableSharedFlow<Long>()
    val seekEvent: SharedFlow<Long> = _seekEvent.asSharedFlow()

    private val _error = MutableSharedFlow<String>(replay = 0)
    val error: SharedFlow<String> = _error.asSharedFlow()

    private val _queue = MutableStateFlow<List<Artifact>>(emptyList())
    val queue: StateFlow<List<Artifact>> = _queue.asStateFlow()

    init {
        // Sync settings from DataStore
        scope.launch {
            settingsDataStore.playbackSpeed.collectLatest { speed ->
                _playbackSpeed.value = speed
                controller?.setPlaybackSpeed(speed)
            }
        }
        scope.launch {
            settingsDataStore.skipSilenceEnabled.collectLatest { enabled ->
                _isSkipSilenceEnabled.value = enabled
                setSkipSilenceInController(enabled)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) {
                startPositionUpdates()
                _currentArtifact.value?.let { analytics.trackPlaybackStart(it) }
            } else {
                stopPositionUpdates()
                saveCurrentPosition()
                _currentArtifact.value?.let { analytics.trackPlaybackPause(it, _currentPosition.value) }
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            _playbackState.value = state
            _isBuffering.value = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) {
                _durationMs.value = controller?.duration?.coerceAtLeast(0) ?: 0
                errorRetryCount = 0 // Reset retry count on success
            }
            if (state == Player.STATE_ENDED) {
                _currentArtifact.value?.let { artifact ->
                    analytics.trackPlaybackComplete(artifact)
                    // Clear saved position on completion
                    scope.launch {
                        engagementRepository.updateLastPosition(artifact.id, 0L)
                    }
                    scope.launch {
                        _playbackCompletedEvent.emit(artifact.id)
                    }
                }
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val message = when (error.errorCode) {
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                    if (errorRetryCount < MAX_RETRIES) {
                        attemptRetry()
                        "Connection lost. Retrying... (${errorRetryCount + 1})"
                    } else {
                        "Network connection lost. Please check your internet."
                    }
                }
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> 
                    "Audio file not found or inaccessible."
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED -> 
                    "Error decoding audio. The file might be corrupted."
                else -> error.message ?: "An unexpected playback error occurred."
            }
            analytics.trackPlaybackError(_currentArtifact.value, message)
            scope.launch {
                _error.emit(message)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val artifact = _queue.value.find { it.id == mediaItem?.mediaId }
            _currentArtifact.value = artifact
            
            if (artifact != null) {
                scope.launch {
                    settingsDataStore.updateLastArtifactId(artifact.id)
                    val index = controller?.currentMediaItemIndex ?: 0
                    settingsDataStore.updateQueue(_queue.value.map { it.id }, index)
                }
            }
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
                // Restore speed/skip silence on init
                controller?.setPlaybackSpeed(_playbackSpeed.value)
                setSkipSilenceInController(_isSkipSilenceEnabled.value)
                
                // Attempt to restore previous playback state if current player is idle
                if (controller?.playbackState == Player.STATE_IDLE) {
                    restorePlaybackState(controller!!)
                } else {
                    controller?.let { syncWithController(it) }
                }
                
                controller
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun syncWithController(controller: MediaController) {
        val currentMediaItem = controller.currentMediaItem
        _isPlaying.value = controller.isPlaying
        _playbackState.value = controller.playbackState
        _durationMs.value = controller.duration.coerceAtLeast(0)
        _currentPosition.value = controller.currentPosition

        if (currentMediaItem != null) {
            scope.launch {
                val artifact = artifactRepository.get().getArtifactById(currentMediaItem.mediaId)
                _currentArtifact.value = artifact
                
                val queueItems = mutableListOf<Artifact>()
                for (i in 0 until controller.mediaItemCount) {
                    val item = controller.getMediaItemAt(i)
                    artifactRepository.get().getArtifactById(item.mediaId)?.let { queueItems.add(it) }
                }
                _queue.value = queueItems
            }
        }
    }

    private suspend fun restorePlaybackState(player: MediaController) {
        val lastIds = settingsDataStore.currentQueueIds.first()
        val lastIndex = settingsDataStore.currentQueueIndex.first()
        val lastArtifactId = settingsDataStore.lastArtifactId.first()

        if (lastIds.isNotEmpty()) {
            Log.d("PlaybackSessionManager", "Restoring queue with ${lastIds.size} items")
            val artifacts = lastIds.mapNotNull { id ->
                artifactRepository.get().getArtifactById(id)
            }
            
            if (artifacts.isNotEmpty()) {
                _queue.value = artifacts
                val mediaItems = artifacts.map { createMediaItem(it) }
                
                val currentArtifact = artifacts.getOrNull(lastIndex)
                val pos = if (currentArtifact != null) {
                    engagementRepository.getEngagement(currentArtifact.id)?.lastPositionMs ?: 0L
                } else 0L

                _currentArtifact.value = currentArtifact
                player.setMediaItems(mediaItems, lastIndex, if (pos > 0) pos else C.TIME_UNSET)
                player.prepare()
            }
        } else if (lastArtifactId != null) {
            // Fallback to just the last artifact if no queue was saved
            artifactRepository.get().getArtifactById(lastArtifactId)?.let { artifact ->
                _currentArtifact.value = artifact
                _queue.value = listOf(artifact)
                
                val pos = engagementRepository.getEngagement(artifact.id)?.lastPositionMs ?: 0L
                player.setMediaItem(createMediaItem(artifact), if (pos > 0) pos else C.TIME_UNSET)
                player.prepare()
            }
        }
    }

    fun play(
        artifact: Artifact, 
        collection: List<Artifact> = emptyList(),
        owner: InteractionOwner = InteractionOwner.PUBLIC_PLAYER, 
        initialPosition: Long = 0L,
        playbackType: PlaybackType = PlaybackType.ARTIFACT
    ) {
        scope.launch {
            // Save position of current artifact before switching
            saveCurrentPosition()

            val player = getController() ?: return@launch
            
            _interactionOwner.value = owner
            _activePlayback.value = ActivePlayback(artifact.id, playbackType)
            
            // Check if we are already playing this exact artifact to avoid redundant resets
            if (player.currentMediaItem?.mediaId == artifact.id && player.playbackState != Player.STATE_IDLE) {
                if (initialPosition > 0 && kotlin.math.abs(player.currentPosition - initialPosition) > 2000) {
                    player.seekTo(initialPosition)
                }
                player.play()
                return@launch
            }

            // If a collection is provided, it becomes the new queue.
            // Otherwise, we just play the single artifact (wrapping it in a list).
            val newQueue = if (collection.isNotEmpty()) {
                if (collection.any { it.id == artifact.id }) collection else listOf(artifact) + collection
            } else {
                listOf(artifact)
            }
            
            _queue.value = newQueue
            _currentArtifact.value = artifact

            val startIndex = newQueue.indexOfFirst { it.id == artifact.id }.coerceAtLeast(0)

            val restoredPosition = if (initialPosition == 0L) {
                engagementRepository.getEngagement(artifact.id)?.lastPositionMs ?: 0L
            } else {
                initialPosition
            }

            val mediaItems = newQueue.map { createMediaItem(it) }
            
            player.setMediaItems(mediaItems, startIndex, restoredPosition)
            player.setPlaybackSpeed(_playbackSpeed.value)
            player.prepare()
            player.play()
            
            settingsDataStore.updateLastArtifactId(artifact.id)
            settingsDataStore.updateQueue(newQueue.map { it.id }, startIndex)
        }
    }

    fun addToQueue(artifacts: List<Artifact>) {
        scope.launch {
            val player = getController() ?: return@launch
            val mediaItems = artifacts.map { createMediaItem(it) }
            player.addMediaItems(mediaItems)
            _queue.value = _queue.value + artifacts
        }
    }

    fun playNext(artifact: Artifact) {
        scope.launch {
            val player = getController() ?: return@launch
            val mediaItem = createMediaItem(artifact)
            val nextIndex = if (player.mediaItemCount > 0) player.currentMediaItemIndex + 1 else 0
            player.addMediaItem(nextIndex, mediaItem)
            
            val currentQueue = _queue.value.toMutableList()
            currentQueue.add(nextIndex, artifact)
            _queue.value = currentQueue
        }
    }

    /**
     * Pre-loads an artifact into the player without starting playback.
     */
    fun preCache(artifact: Artifact) {
        scope.launch {
            val player = getController() ?: return@launch
            if (player.isPlaying) return@launch
            
            val mediaItem = createMediaItem(artifact)
            player.addMediaItem(mediaItem)
            player.prepare()
            Log.d("PlaybackSessionManager", "Pre-cached artifact: ${artifact.id}")
        }
    }

    private fun createMediaItem(artifact: Artifact): MediaItem {
        return MediaItem.Builder()
            .setUri(artifact.audioUrl)
            .setMediaId(artifact.id)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(artifact.title)
                    .setArtist(artifact.author.name)
                    .setAlbumTitle("Reflections")
                    .setGenre(artifact.emotion)
                    .setExtras(android.os.Bundle().apply {
                        putString("author_sigil", artifact.author.sigil)
                        putString("avatar_seed", artifact.author.avatarSeed)
                    })
                    .build()
            )
            .build()
    }

    fun setPlaybackSpeed(speed: Float) {
        scope.launch {
            settingsDataStore.updatePlaybackSpeed(speed)
        }
    }

    fun setSkipSilenceEnabled(enabled: Boolean) {
        scope.launch {
            settingsDataStore.updateSkipSilenceEnabled(enabled)
        }
    }

    private fun setSkipSilenceInController(enabled: Boolean) {
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

    fun skipForward(millis: Long = 10_000L) {
        val newPos = (_currentPosition.value + millis).coerceAtMost(_durationMs.value)
        seekTo(newPos)
    }

    fun skipBackward(millis: Long = 10_000L) {
        val newPos = (_currentPosition.value - millis).coerceAtLeast(0L)
        seekTo(newPos)
    }

    fun stop() {
        scope.launch {
            saveCurrentPosition()
            getController()?.stop()
            _interactionOwner.value = InteractionOwner.NONE
            _currentArtifact.value = null
            _activePlayback.value = null
            _isPlaying.value = false
            _queue.value = emptyList()
        }
    }

    fun stopIfType(type: PlaybackType) {
        if (_activePlayback.value?.playbackType == type) {
            stop()
        }
    }

    fun stopIfOwner(owner: InteractionOwner) {
        if (_interactionOwner.value == owner) {
            stop()
        }
    }

    private fun saveCurrentPosition() {
        val artifact = _currentArtifact.value ?: return
        val pos = _currentPosition.value
        val dur = _durationMs.value
        
        scope.launch {
            if (pos < 1000L || (dur > 0 && pos > dur - 2000L)) {
                // Too close to start or end - clear position to start fresh next time
                engagementRepository.updateLastPosition(artifact.id, 0L)
            } else {
                engagementRepository.updateLastPosition(artifact.id, pos)
            }
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
                        saveCurrentPosition()
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

    private fun attemptRetry() {
        errorRetryCount++
        scope.launch {
            delay(2000L * errorRetryCount) // Exponential backoff
            controller?.prepare()
            controller?.play()
        }
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
