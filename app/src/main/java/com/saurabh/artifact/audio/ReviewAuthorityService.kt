package com.saurabh.artifact.audio

import android.os.SystemClock
import androidx.media3.common.Player
import com.saurabh.artifact.audio.validation.DefaultReviewTracker
import com.saurabh.artifact.audio.validation.ReviewProgress
import com.saurabh.artifact.audio.validation.ReviewTracker
import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.domain.review.comments.CommentUnlockPolicy
import com.saurabh.artifact.domain.review.comments.CommentUnlockValidator
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.repository.EngagementRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Authoritative service for review validation and engagement tracking.
 * Unifies logic for Comment Unlocking and Resume-Play.
 * Uses CommentUnlockPolicy for its authoritative validation.
 */
@Singleton
class ReviewAuthorityService @Inject constructor(
    private val playbackSessionManager: PlaybackSessionManager,
    private val engagementRepository: EngagementRepository,
    private val commentValidator: CommentUnlockValidator,
    private val commentPolicy: CommentUnlockPolicy
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var activeTracker: ReviewTracker? = null
    private var lastTickTime: Long = 0L
    private var completionTriggered = false

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
                        val progress = tracker.progress
                        _currentProgress.value = progress
                        
                        if (progress.isValidationMet && !completionTriggered) {
                            handleCompletion(progress)
                        }
                    }
                } else {
                    lastTickTime = 0L
                    // Save last position immediately when paused
                    activeTracker?.let { tracker ->
                         engagementRepository.updateLastPosition(tracker.progress.artifactId, pos)
                    }
                }
            }
        }

        // Periodic debounced persistence (for coverage/effort)
        scope.launch {
            @OptIn(FlowPreview::class)
            _currentProgress
                .filterNotNull()
                .sample(5000.milliseconds) 
                .collect { progress ->
                    engagementRepository.saveEngagement(progress.evidence)
                }
        }

        // Observe State for End Events
        scope.launch {
            playbackSessionManager.playbackState.collect { state ->
                if (state == Player.STATE_ENDED) {
                    activeTracker?.onPlaybackEnded()
                    val progress = activeTracker?.progress
                    _currentProgress.value = progress
                    progress?.let { 
                        engagementRepository.saveEngagement(it.evidence)
                        if (it.isValidationMet && !completionTriggered) handleCompletion(it) 
                    }
                }
            }
        }

        // Observe Seeks
        scope.launch {
            playbackSessionManager.seekEvent.collect { _ ->
                activeTracker?.onSeekPerformed()
            }
        }
    }

    /**
     * Initializes a review session, ensuring the [Publishing Flow Invariants](file:///docs/architecture/PublishingFlowInvariants.md)
     * are maintained regarding field ownership and recovery.
     */
    private suspend fun initializeSession(artifact: Artifact) {
        if (activeTracker?.progress?.artifactId == artifact.id) return

        val evidence = engagementRepository.getEngagement(artifact.id)
            .getOrNull()
            ?.copy(durationMs = artifact.durationMs) // Phase 6: Always sync with authoritative duration
            ?: EngagementEvidence(
                artifactId = artifact.id,
                versionTag = "v1",
                durationMs = artifact.durationMs,
                audioChecksum = artifact.checksum,
            )

        activeTracker = DefaultReviewTracker(
            initialEvidence = evidence,
            segmentSizer = { commentPolicy.getSegmentSizeMs(it) },
            validator = { commentValidator.validate(it, commentPolicy) }
        )
        _currentProgress.value = activeTracker?.progress
        lastTickTime = SystemClock.elapsedRealtime()
        completionTriggered = false
    }

    private suspend fun finalizeSession() {
        activeTracker?.let { tracker ->
            val progress = tracker.progress
            // Save one last time to ensure position is captured
            engagementRepository.saveEngagement(progress.evidence)
            engagementRepository.updateLastPosition(progress.artifactId, progress.evidence.lastPositionMs)
        }
        activeTracker = null
        _currentProgress.value = null
    }

    private fun handleCompletion(progress: ReviewProgress) {
        if (completionTriggered) return
        completionTriggered = true

        android.util.Log.d("STUDIO_TRACE", "ReviewAuthorityService: handleCompletion for ${progress.artifactId} (LIFECYCLE_TRACE)")
        scope.launch(Dispatchers.IO) {
            engagementRepository.saveEngagement(progress.evidence)
        }
    }
}
