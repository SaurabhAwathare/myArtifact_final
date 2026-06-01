package com.saurabh.artifact.audio

import android.os.SystemClock
import androidx.media3.common.Player
import com.saurabh.artifact.audio.validation.DefaultReviewTracker
import com.saurabh.artifact.audio.validation.ReviewProgress
import com.saurabh.artifact.audio.validation.ReviewTracker
import com.saurabh.artifact.audio.validation.ReviewValidator
import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.domain.review.ReviewPolicy
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.repository.CommentUnlockRepository
import com.saurabh.artifact.repository.EngagementRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authoritative service for review validation and engagement tracking.
 * Unifies logic for Draft Publication, Comment Unlocking, and Resume-Play.
 * Upgraded with event-driven updates and unified persistence via EngagementRepository.
 */
@Singleton
class ReviewAuthorityService @Inject constructor(
    private val playbackSessionManager: PlaybackSessionManager,
    private val engagementRepository: EngagementRepository,
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
        // Session Lifecycle
        scope.launch {
            playbackSessionManager.currentArtifact.collect { artifact ->
                if (artifact == null) {
                    finalizeSession()
                } else {
                    initializeSession(artifact)
                }
            }
        }

        // Event-driven Ticking
        scope.launch {
            combine(
                playbackSessionManager.currentPosition,
                playbackSessionManager.isPlaying,
                playbackSessionManager.playbackSpeed
            ) { pos, isPlaying, speed ->
                Triple(pos, isPlaying, speed)
            }.collect { (pos, isPlaying, speed) ->
                if (isPlaying) {
                    val now = SystemClock.elapsedRealtime()
                    val delta = if (lastTickTime == 0L) 0L else now - lastTickTime
                    lastTickTime = now

                    activeTracker?.let { tracker ->
                        tracker.onPlaybackTick(pos, delta, speed)
                        val progress = tracker.getProgress()
                        _currentProgress.value = progress
                        
                        if (progress.isValidationMet) {
                            handleCompletion(progress)
                        }
                    }
                } else {
                    lastTickTime = 0L
                    // Save last position immediately when paused
                    activeTracker?.let { tracker ->
                         engagementRepository.updateLastPosition(tracker.getProgress().artifactId, pos)
                    }
                }
            }
        }

        // Periodic debounced persistence (for coverage/effort)
        scope.launch {
            @OptIn(FlowPreview::class)
            _currentProgress
                .filterNotNull()
                .sample(5000L) // Persist at most every 5 seconds
                .collect { progress ->
                    engagementRepository.saveEngagement(progress.evidence)
                }
        }

        // Observe State for End Events
        scope.launch {
            playbackSessionManager.playbackState.collect { state ->
                if (state == Player.STATE_ENDED) {
                    activeTracker?.onPlaybackEnded()
                    val progress = activeTracker?.getProgress()
                    _currentProgress.value = progress
                    progress?.let { 
                        engagementRepository.saveEngagement(it.evidence)
                        if (it.isValidationMet) handleCompletion(it) 
                    }
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
        val evidence = engagementRepository.getEngagement(artifact.id) ?: EngagementEvidence(
            artifactId = artifact.id,
            versionTag = "v1",
            durationMs = artifact.durationMs,
            audioChecksum = artifact.checksum ?: ""
        )

        activeTracker = DefaultReviewTracker(
            initialEvidence = evidence,
            policy = ReviewPolicy(),
            validator = validator
        )
        _currentProgress.value = activeTracker?.getProgress()
        lastTickTime = SystemClock.elapsedRealtime()
    }

    private suspend fun finalizeSession() {
        val progress = activeTracker?.getProgress() ?: return
        engagementRepository.saveEngagement(progress.evidence)
        activeTracker = null
        _currentProgress.value = null
    }

    private fun handleCompletion(progress: ReviewProgress) {
        scope.launch(Dispatchers.IO) {
            engagementRepository.saveEngagement(progress.evidence)
            commentUnlockRepository.unlockArtifact(progress.artifactId)
        }
    }
}
