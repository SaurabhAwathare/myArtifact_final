package com.saurabh.artifact.audio

import android.os.SystemClock
import androidx.media3.common.Player
import com.saurabh.artifact.audio.validation.DefaultReviewTracker
import com.saurabh.artifact.audio.validation.ReviewProgress
import com.saurabh.artifact.audio.validation.ReviewTracker
import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.domain.review.ReviewTrackingVersion
import com.saurabh.artifact.domain.review.publishing.PublishingReviewPolicy
import com.saurabh.artifact.domain.review.publishing.PublishingReviewValidator
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.repository.EngagementRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Authoritative service for engagement tracking and playback evidence.
 * Unifies logic for draft review validation and listener engagement.
 */
@Singleton
class ReviewAuthorityService @Inject constructor(
    private val playbackSessionManager: PlaybackSessionManager,
    private val engagementRepository: EngagementRepository,
    private val validator: PublishingReviewValidator,
    private val policy: PublishingReviewPolicy
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
        // Session Lifecycle: Use collectLatest to ensure only the CURRENT artifact setup is active.
        scope.launch {
            playbackSessionManager.currentArtifact.collectLatest { artifact ->
                if (artifact == null) {
                    finalizeSession()
                } else {
                    // R036 Alignment: Ensure previous session is finalized before starting new one
                    if (activeTracker != null && activeTracker?.progress?.artifactId != artifact.id) {
                        finalizeSession()
                    }
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
                    activeTracker?.let { tracker ->
                        // Phase 12: Terminal Coverage Tracking Fix
                        // 1. Obtain the final playback position.
                        val finalPos = playbackSessionManager.currentPosition.value
                        val speed = playbackSessionManager.playbackSpeed.value
                        val now = SystemClock.elapsedRealtime()
                        
                        // Process one final tracker.onPlaybackTick(...)
                        // We use the delta since last tick to ensure terminal coverage.
                        val delta = if (lastTickTime == 0L) 0L else now - lastTickTime
                        lastTickTime = now

                        tracker.onPlaybackTick(finalPos, delta, speed)
                        
                        // 2. Then invoke tracker.onPlaybackEnded()
                        tracker.onPlaybackEnded()

                        // 3. Then continue the normal validation flow.
                        val progress = tracker.progress
                        _currentProgress.value = progress
                        engagementRepository.saveEngagement(progress.evidence)
                        if (progress.isValidationMet && !completionTriggered) {
                            handleCompletion(progress)
                        }
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
     * Initializes a review session.
     */
    private suspend fun initializeSession(artifact: Artifact) {
        if (activeTracker?.progress?.artifactId == artifact.id) return

        val evidence = engagementRepository.getEngagement(artifact.id)
            .getOrNull()
            ?.copy(durationMs = artifact.durationMs)
            ?: EngagementEvidence(
                artifactId = artifact.id,
                versionTag = "v1",
                durationMs = artifact.durationMs,
                audioChecksum = artifact.checksum,
                reviewTrackingVersion = ReviewTrackingVersion.CURRENT,
                segmentSizeMs = policy.getSegmentSizeMs(artifact.durationMs, ReviewTrackingVersion.CURRENT)
            )

        activeTracker = DefaultReviewTracker(
            initialEvidence = evidence,
            segmentSizer = { dur, ver -> policy.getSegmentSizeMs(dur, ver) },
            validator = { validator.validate(it, policy) }
        )
        _currentProgress.value = activeTracker?.progress
        lastTickTime = SystemClock.elapsedRealtime()
        completionTriggered = false
    }

    private suspend fun finalizeSession() {
        val tracker = activeTracker ?: return
        val progress = tracker.progress
        
        withContext(NonCancellable) {
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

        com.saurabh.artifact.diagnostics.ArtifactLogger.i(
            com.saurabh.artifact.diagnostics.DiagnosticCategory.COMMENT, 
            "COMMENT_UNLOCK_MET", 
            mapOf(com.saurabh.artifact.diagnostics.LogKeys.ARTIFACT_ID to progress.artifactId)
        )

        scope.launch(Dispatchers.IO) {
            engagementRepository.saveEngagement(progress.evidence)
        }
    }
}
