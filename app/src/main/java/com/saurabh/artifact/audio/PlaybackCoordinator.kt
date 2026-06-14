package com.saurabh.artifact.audio

import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.PlayableArtifact
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * High-level coordinator for all playback types.
 * Manages the "why" and "when" of playback, while [PlaybackSessionManager] handles the "how".
 */
@Singleton
class PlaybackCoordinator @Inject constructor(
    private val playbackSessionManager: PlaybackSessionManager,
    private val reviewSessionManager: ReviewSessionManager,
    private val transientPlayerManager: TransientPlayerManager,
    private val analytics: PlaybackAnalyticsManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val currentArtifact = playbackSessionManager.currentArtifact
    val isPlaying = playbackSessionManager.isPlaying
    val isBuffering = playbackSessionManager.isBuffering
    
    val currentPosition: Flow<Duration> = playbackSessionManager.currentPosition.map { it.milliseconds }
    private val positionSync = playbackSessionManager.positionSync
    
    val duration: Flow<Duration> = playbackSessionManager.durationMs.map { it.milliseconds }
    
    val playbackSpeed = playbackSessionManager.playbackSpeed
    val isSkipSilenceEnabled = playbackSessionManager.isSkipSilenceEnabled
    val playbackCompletedEvent = playbackSessionManager.playbackCompletedEvent
    val activePlayback = playbackSessionManager.activePlayback
    val error = playbackSessionManager.error

    val reviewProgress = reviewSessionManager.reviewProgress

    private val _sleepTimerRemaining = MutableStateFlow<Duration?>(null)
    val sleepTimerRemaining: StateFlow<Duration?> = _sleepTimerRemaining.asStateFlow()
    private var sleepTimerJob: Job? = null

    private val _scrubbingPosition = MutableStateFlow<Duration?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val smoothPosition: Flow<Duration> = combine(
        positionSync,
        _scrubbingPosition
    ) { sync, scrubbing ->
        // If the user is scrubbing, that takes precedence over the service's position
        scrubbing ?: sync
    }.flatMapLatest { state ->
        when (state) {
            is Duration -> flowOf(state) // Manual scrubbing position
            is PlaybackSessionManager.PositionSync -> {
                if (state.isPlaying) {
                    flow {
                        while (true) {
                            val elapsed = android.os.SystemClock.elapsedRealtime() - state.timestampMs
                            val currentMs = state.positionMs + (elapsed * state.speed).toLong()
                            emit(currentMs.milliseconds)
                            delay(32.milliseconds)
                        }
                    }
                } else {
                    flowOf(state.positionMs.milliseconds)
                }
            }
            else -> flowOf(Duration.ZERO)
        }
    }.shareIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000),
        replay = 1
    )

    init {
        // Automatically stop ambient sound when main playback is cleared
        scope.launch {
            activePlayback.collect { active ->
                if (active == null) {
                    transientPlayerManager.stop()
                }
            }
        }
    }

    /**
     * Start playing a persistent artifact (e.g., from the main feed).
     */
    fun playArtifact(artifact: Artifact, collection: List<Artifact> = emptyList(), initialPosition: Duration = Duration.ZERO) {
        playbackSessionManager.play(
            artifact = artifact,
            collection = collection,
            playbackType = PlaybackType.ARTIFACT,
            initialPosition = initialPosition.inWholeMilliseconds
        )
    }

    /**
     * Start a draft preview. These are typically transient and might be stopped 
     * by the coordinator when the user leaves the draft edit screen.
     */
    fun playDraftPreview(draftId: String) {
        reviewSessionManager.startReview(draftId)
    }

    fun togglePlayPause() {
        playbackSessionManager.togglePlayPause()
    }

    fun stop() {
        playbackSessionManager.stop()
        transientPlayerManager.stop()
    }

    /**
     * Requests to stop playback only if it's of a certain type.
     * Useful for ViewModels to call in onCleared() without stopping persistent playback.
     */
    fun requestStop(type: PlaybackType) {
        if (playbackSessionManager.activePlayback.value?.playbackType == type) {
            stop()
        }
    }

    fun seekTo(position: Duration) {
        playbackSessionManager.seekTo(position.inWholeMilliseconds)
    }

    /**
     * Called by the UI when the user starts or is currently scrubbing.
     * Prevents the UI from jumping back to the old playback position during the drag.
     */
    fun updateScrubbingPosition(position: Duration?) {
        _scrubbingPosition.value = position
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

    fun trackPlayableStart(playable: PlayableArtifact) {
        analytics.trackPlayableStart(playable)
    }

    fun startSleepTimer(duration: Duration) {
        sleepTimerJob?.cancel()
        if (duration == Duration.ZERO) {
            _sleepTimerRemaining.value = null
            return
        }

        _sleepTimerRemaining.value = duration

        sleepTimerJob = scope.launch {
            var remaining = duration
            while (remaining > Duration.ZERO) {
                delay(1.seconds)
                remaining -= 1.seconds
                _sleepTimerRemaining.value = remaining.coerceAtLeast(Duration.ZERO)
                if (!isPlaying.value) continue
            }
            stop()
            _sleepTimerRemaining.value = null
        }
    }
}
