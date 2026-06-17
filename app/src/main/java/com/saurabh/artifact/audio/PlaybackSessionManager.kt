package com.saurabh.artifact.audio

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.EngagementRepository
import com.saurabh.artifact.util.CoroutineExceptionHandlerUtils
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.cancelChildren
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
    private val artifactRepository: Lazy<ArtifactRepository>,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + 
        Dispatchers.Main + 
        CoroutineExceptionHandlerUtils.create("PlaybackSessionManager", "Playback scope failure")
    )
    private val controllerLock = Mutex()
    
    data class PositionSync(
        val positionMs: Long = 0L,
        val timestampMs: Long = android.os.SystemClock.elapsedRealtime(),
        val speed: Float = 1f,
        val isPlaying: Boolean = false,
    )

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val _activePlayback = MutableStateFlow<ActivePlayback?>(null)
    val activePlayback: StateFlow<ActivePlayback?> = _activePlayback.asStateFlow()
    
    private val _currentArtifact = MutableStateFlow<Artifact?>(null)
    val currentArtifact: StateFlow<Artifact?> = _currentArtifact.asStateFlow()

    private var transcriptFetchJob: Job? = null

    private val _isPlaying = MutableStateFlow(value = false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _positionSync = MutableStateFlow(PositionSync())
    val positionSync: StateFlow<PositionSync> = _positionSync.asStateFlow()

    private val _playbackState = MutableStateFlow(value = Player.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(value = 1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _isSkipSilenceEnabled = MutableStateFlow(value = false)
    val isSkipSilenceEnabled: StateFlow<Boolean> = _isSkipSilenceEnabled.asStateFlow()

    private var positionUpdateJob: Job? = null
    private var cleanupSyncJob: Job? = null
    
    private var errorRetryCount = 0
    private val maxRetries = 3

    private val _isBuffering = MutableStateFlow(value = false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _playbackCompletedEvent = MutableSharedFlow<String>(replay = 0)
    val playbackCompletedEvent: SharedFlow<String> = _playbackCompletedEvent.asSharedFlow()

    private val _seekEvent = MutableSharedFlow<Long>()
    val seekEvent: SharedFlow<Long> = _seekEvent.asSharedFlow()

    private val _error = MutableSharedFlow<String>(replay = 0)
    val error: SharedFlow<String> = _error.asSharedFlow()

    private val _queue = MutableStateFlow<List<Artifact>>(emptyList())

    init {
        // Sync settings from DataStore
        scope.launch {
            settingsDataStore.playbackSpeed.collectLatest { speed ->
                // Only apply global speed updates if no playback is active 
                // or if the active playback is a standard artifact
                val activeType = _activePlayback.value?.playbackType
                if ((activeType == null) || (activeType == PlaybackType.ARTIFACT)) {
                    _playbackSpeed.value = speed
                    controller?.setPlaybackSpeed(speed)
                }
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
            updatePositionSync()
            if (isPlaying) {
                startPositionUpdates()
                _currentArtifact.value?.let { analytics.trackPlaybackStart(it) }
            } else {
                stopPositionUpdates()
                _currentArtifact.value?.let { analytics.trackPlaybackPause(it, _currentPosition.value) }
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            _playbackState.value = state
            _isBuffering.value = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) {
                _durationMs.value = controller?.duration?.coerceAtLeast(0) ?: 0
                errorRetryCount = 0 // Reset retry count on success
                updatePositionSync()
            }
            if (state == Player.STATE_ENDED) {
                updatePositionSync()
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

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            updatePositionSync()
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            _playbackSpeed.value = playbackParameters.speed
            updatePositionSync()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val message = when (error.errorCode) {
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> {
                    if (errorRetryCount < maxRetries) {
                        attemptRetry()
                        "Searching for connection... (${errorRetryCount + 1})"
                    } else {
                        "Your connection seems to have drifted away."
                    }
                }
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                    // This often covers 404 (Not Found) or 403 (Forbidden)
                    "This reflection's link has become inaccessible."
                }
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> 
                    "The audio file could not be found."
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED -> 
                    "This reflection's voice is unclear (decoding error)."
                else -> "A quiet moment was interrupted by a system error."
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
                // Lazy load transcript if missing
                if (artifact.transcript.isEmpty() && !artifact.transcriptUrl.isNullOrEmpty()) {
                    loadTranscriptLazy(artifact)
                }

                scope.launch {
                    settingsDataStore.updateLastArtifactId(artifact.id)
                    val index = controller?.currentMediaItemIndex ?: 0
                    settingsDataStore.updateQueue(_queue.value.map { it.id }, index)
                }
            }
        }
    }

    private fun loadTranscriptLazy(artifact: Artifact) {
        transcriptFetchJob?.cancel()
        transcriptFetchJob = scope.launch(Dispatchers.IO) {
            val url = artifact.transcriptUrl ?: return@launch
            artifactRepository.get().fetchTranscript(url).onSuccess { segments ->
                withContext(Dispatchers.Main) {
                    // Update the current artifact if it's still the one playing
                    if (_currentArtifact.value?.id == artifact.id) {
                        _currentArtifact.value = _currentArtifact.value?.copy(transcript = segments)
                    }
                    // Update the artifact in the queue as well
                    _queue.value = _queue.value.map { 
                        if (it.id == artifact.id) it.copy(transcript = segments) else it 
                    }
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
            
            val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, sessionToken).buildAsync()
            
            try {
                val instance = future.await()
                instance.addListener(playerListener)
                controller = instance
                controllerFuture = future
                _isPlaying.value = controller?.isPlaying ?: false
                _durationMs.value = controller?.duration?.coerceAtLeast(0) ?: 0
                // Restore speed/skip silence on init
                controller?.setPlaybackSpeed(_playbackSpeed.value)
                setSkipSilenceInController(_isSkipSilenceEnabled.value)
                
                // Attempt to restore previous playback state if current player is idle
                controller?.let { syncWithController(it) }
                
                controller
            } catch (e: Exception) {
                Log.e("PlaybackSessionManager", "Failed to init MediaController", e)
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
                val artifactResult = artifactRepository.get().getArtifactById(currentMediaItem.mediaId)
                _currentArtifact.value = artifactResult.getOrNull()
                
                val queueItems = mutableListOf<Artifact>()
                for (i in 0 until controller.mediaItemCount) {
                    val item = controller.getMediaItemAt(i)
                    artifactRepository.get().getArtifactById(item.mediaId).onSuccess { queueItems.add(it) }
                }
                _queue.value = queueItems
            }
        }
    }

    fun play(
        artifact: Artifact, 
        collection: List<Artifact> = emptyList(),
        initialPosition: Long = 0L,
        playbackType: PlaybackType = PlaybackType.ARTIFACT
    ) {
        scope.launch {
            val player = getController() ?: return@launch
            
            _activePlayback.value = ActivePlayback(artifact.id, playbackType)
            
            // Check if we are already playing this exact artifact to avoid redundant resets
            if ((player.currentMediaItem?.mediaId == artifact.id) && (player.playbackState != Player.STATE_IDLE)) {
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
                engagementRepository.getEngagement(artifact.id).getOrNull()?.lastPositionMs ?: 0L
            } else {
                initialPosition
            }

            val mediaItems = newQueue.map { createMediaItem(it) }
            
            val savedSpeed = settingsDataStore.playbackSpeed.first()
            val targetSpeed = if (playbackType == PlaybackType.ARTIFACT) savedSpeed else 1.0f
            
            player.setMediaItems(mediaItems, startIndex, restoredPosition)
            player.setPlaybackSpeed(targetSpeed)
            _playbackSpeed.value = targetSpeed
            
            player.prepare()
            player.play()
            
            settingsDataStore.updateLastArtifactId(artifact.id)
            settingsDataStore.updateQueue(newQueue.map { it.id }, startIndex)
        }
    }

    /**
     * Pre-loads an artifact into the cache in the background.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun preCache(artifact: Artifact) {
        MediaPreCacher.preCache(context, artifact.audioUrl)
        Log.d("PlaybackSessionManager", "Enqueued background pre-cache: ${artifact.id}")
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
                    .setExtras(
                        android.os.Bundle().apply {
                            putString("author_sigil", artifact.author.sigil)
                            putString("avatar_seed", artifact.author.avatarSeed)
                        }
                    )
                    .build()
            )
            .build()
    }

    fun setPlaybackSpeed(speed: Float) {
        scope.launch {
            val player = getController()
            player?.setPlaybackSpeed(speed)
            _playbackSpeed.value = speed
            // updatePositionSync() is now called from onPlaybackParametersChanged

            // Only persist to DataStore if we are playing a standard artifact
            if (_activePlayback.value?.playbackType == PlaybackType.ARTIFACT) {
                settingsDataStore.updatePlaybackSpeed(speed)
            }
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
                androidx.media3.session.SessionCommand(
                    "SET_SKIP_SILENCE",
                    android.os.Bundle().apply {
                        putBoolean("enabled", enabled)
                    }
                ),
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
            updatePositionSync()
            _seekEvent.emit(position)
        }
    }

    fun stop() {
        Log.d("LOOP_FIX", "PlaybackSessionManager.stop(): clearing playback state")
        // Synchronously clear state to prevent race conditions in UI/Navigation
        _currentArtifact.value = null
        _activePlayback.value = null
        _isPlaying.value = false
        _queue.value = emptyList()
        
        scope.launch {
            getController()?.stop()
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (isActive) {
                controller?.let { p ->
                    val pos = p.currentPosition
                    val dur = p.duration.coerceAtLeast(0)
                    _currentPosition.value = pos
                    _durationMs.value = dur
                    updatePositionSync()
                }
                delay(100.milliseconds)
            }
        }
    }

    private fun updatePositionSync() {
        val p = controller ?: return
        _positionSync.value = PositionSync(
            positionMs = p.currentPosition,
            timestampMs = android.os.SystemClock.elapsedRealtime(),
            speed = _playbackSpeed.value,
            isPlaying = p.isPlaying
        )
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
            delay((2 * errorRetryCount).seconds) // Exponential backoff
            controller?.prepare()
            controller?.play()
        }
    }

    fun release() {
        cleanupSyncJob?.cancel()
        positionUpdateJob?.cancel()
        controller?.removeListener(playerListener)
        controllerFuture?.let { 
            try {
                MediaController.releaseFuture(it) 
            } catch (e: Exception) {
                Log.e("PlaybackSessionManager", "Error releasing controller future", e)
            }
        }
        controller = null
        controllerFuture = null
        scope.coroutineContext.cancelChildren() // Cancel all pending tasks but keep the scope alive for future use
    }
}
