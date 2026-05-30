package com.saurabh.artifact.audio

import android.os.SystemClock
import androidx.media3.common.Player
import com.saurabh.artifact.audio.validation.DefaultReviewTracker
import com.saurabh.artifact.audio.validation.ReviewProgress
import com.saurabh.artifact.audio.validation.ReviewTracker
import com.saurabh.artifact.audio.validation.ReviewValidator
import com.saurabh.artifact.data.local.ArtifactReviewEvidence
import com.saurabh.artifact.data.local.ReviewDao
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.repository.CommentUnlockRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authoritative service for review validation.
 * Unifies logic for Draft Publication and Comment Unlocking.
 */
@Singleton
class ReviewAuthorityService @Inject constructor(
    private val playbackSessionManager: PlaybackSessionManager,
    private val reviewDao: ReviewDao,
    private val commentUnlockRepository: CommentUnlockRepository,
    private val validator: ReviewValidator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var activeTracker: ReviewTracker? = null
    private var lastTickTime: Long = 0L

    private val _currentProgress = MutableStateFlow<ReviewProgress?>(null)
    val currentProgress: StateFlow<ReviewProgress?> = _currentProgress.asStateFlow()

    init {
        observePlayback()
    }

    private fun observePlayback() {
        scope.launch {
            playbackSessionManager.currentArtifact.collect { artifact ->
                if (artifact == null) {
                    finalizeSession()
                } else {
                    initializeSession(artifact)
                }
            }
        }

        // Periodic Update Loop (Ticking)
        scope.launch {
            while (isActive) {
                if (playbackSessionManager.isPlaying.value) {
                    val now = SystemClock.elapsedRealtime()
                    val delta = if (lastTickTime == 0L) 0L else now - lastTickTime
                    lastTickTime = now
                    
                    activeTracker?.let { tracker ->
                        tracker.onPlaybackTick(
                            currentPosMs = playbackSessionManager.currentPosition.value,
                            realElapsedMs = delta,
                            playbackSpeed = playbackSessionManager.playbackSpeed.value
                        )
                        _currentProgress.value = tracker.getProgress()
                        
                        // Check for completion
                        if (_currentProgress.value?.isValidationMet == true) {
                            handleCompletion(tracker.getProgress())
                        }
                    }
                } else {
                    lastTickTime = 0L
                }
                delay(1000) // 1 second resolution for persistence and updates
            }
        }

        // Observe State for End Events
        scope.launch {
            playbackSessionManager.playbackState.collect { state ->
                if (state == Player.STATE_ENDED) {
                    activeTracker?.onPlaybackEnded()
                    _currentProgress.value = activeTracker?.getProgress()
                    _currentProgress.value?.let { if (it.isValidationMet) handleCompletion(it) }
                }
            }
        }

        // Observe Seeks
        scope.launch {
            playbackSessionManager.seekEvent.collect { position ->
                activeTracker?.onSeekPerformed(position)
            }
        }
    }

    private suspend fun initializeSession(artifact: Artifact) {
        val evidence = reviewDao.getEvidence(artifact.id)
        activeTracker = DefaultReviewTracker(
            artifactId = artifact.id,
            durationMs = artifact.durationMs,
            validator = validator,
            initialP1 = evidence?.coverageP1 ?: 0L,
            initialP2 = evidence?.coverageP2 ?: 0L,
            initialEffortMs = evidence?.cumulativeEffortMs ?: 0L,
            initialReachedEnd = evidence?.hasReachedEnd ?: false,
            initialFurthestMs = evidence?.furthestPositionMs ?: 0L
        )
        _currentProgress.value = activeTracker?.getProgress()
        lastTickTime = SystemClock.elapsedRealtime()
    }

    private suspend fun finalizeSession() {
        val progress = activeTracker?.getProgress() ?: return
        persistProgress(progress)
        activeTracker = null
        _currentProgress.value = null
    }

    private fun handleCompletion(progress: ReviewProgress) {
        scope.launch(Dispatchers.IO) {
            persistProgress(progress)
            commentUnlockRepository.unlockArtifact(progress.artifactId)
        }
    }

    private suspend fun persistProgress(progress: ReviewProgress) {
        withContext(Dispatchers.IO) {
            reviewDao.insertEvidence(
                ArtifactReviewEvidence(
                    artifactId = progress.artifactId,
                    durationMs = progress.durationMs,
                    coverageP1 = progress.rawP1,
                    coverageP2 = progress.rawP2,
                    cumulativeEffortMs = progress.totalTimeListenedMs,
                    hasReachedEnd = progress.hasReachedEnd,
                    furthestPositionMs = progress.furthestPositionMs
                )
            )
        }
    }

    fun onSeekPerformed(targetMs: Long) {
        activeTracker?.onSeekPerformed(targetMs)
    }
}
