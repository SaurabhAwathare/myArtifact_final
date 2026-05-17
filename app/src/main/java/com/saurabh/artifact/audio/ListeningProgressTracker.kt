package com.saurabh.artifact.audio

import android.util.Log
import com.saurabh.artifact.model.PlaybackSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ListeningProgressTracker @Inject constructor(
    private val audioPlayer: AudioPlayer
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    
    private val _sessionState = MutableStateFlow<PlaybackSessionState?>(null)
    val sessionState: StateFlow<PlaybackSessionState?> = _sessionState.asStateFlow()

    private val THRESHOLD_PERCENT = 0.95f
    private val SCRUB_TOLERANCE_MS = 2000L // 2 seconds leeway for minor jumps

    init {
        observePlayback()
    }

    private fun observePlayback() {
        scope.launch {
            combine(
                audioPlayer.currentArtifact,
                audioPlayer.currentPosition,
                audioPlayer.duration
            ) { artifact, position, duration ->
                Triple(artifact, position, duration)
            }.collect { (artifact, position, duration) ->
                if (artifact == null) {
                    _sessionState.value = null
                    return@collect
                }

                val currentState = _sessionState.value ?: PlaybackSessionState(artifactId = artifact.id, totalDurationMs = duration)
                
                // If artifact changed, reset or load previous state (loading handled by ViewModel/Repo usually)
                val activeState = if (currentState.artifactId != artifact.id) {
                    PlaybackSessionState(artifactId = artifact.id, totalDurationMs = duration)
                } else {
                    currentState.copy(totalDurationMs = duration)
                }

                // Anti-scrubbing logic: 
                // Only advance furthestPosition if currentPosition is within a small window of furthestPosition
                // or if it's behind the furthestPosition (re-listening).
                // If the user skips ahead significantly, furthestPosition doesn't move.
                
                val updatedFurthest = if (position > activeState.furthestPositionMs) {
                    if (position <= activeState.furthestPositionMs + SCRUB_TOLERANCE_MS) {
                        position
                    } else {
                        // User scrubbed forward too much, don't update furthestPosition
                        activeState.furthestPositionMs
                    }
                } else {
                    activeState.furthestPositionMs
                }

                val isUnlocked = if (duration > 0) {
                    val progress = updatedFurthest.toFloat() / duration
                    if (progress >= THRESHOLD_PERCENT && !activeState.isThresholdMet) {
                        Log.d("ReviewDebug", "SETTING REVIEW COMPLETE TRUE for ${artifact.id}")
                    }
                    Log.d(
                        "ReviewDebug",
                        "position=$position duration=$duration furthest=$updatedFurthest progress=$progress"
                    )
                    progress >= THRESHOLD_PERCENT
                } else {
                    false
                }

                _sessionState.value = activeState.copy(
                    furthestPositionMs = updatedFurthest,
                    isThresholdMet = isUnlocked || activeState.isThresholdMet
                )
            }
        }
    }

    /**
     * Resets the tracker for the current artifact if the user wants to start over,
     * though typically we want to preserve furthest progress.
     */
    fun resetProgress() {
        val current = _sessionState.value ?: return
        _sessionState.value = current.copy(furthestPositionMs = 0L, isThresholdMet = false)
    }
}
