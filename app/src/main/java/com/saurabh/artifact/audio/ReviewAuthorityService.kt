package com.saurabh.artifact.audio

import android.os.SystemClock
import androidx.media3.common.Player
import com.saurabh.artifact.audio.validation.DefaultReviewTracker
import com.saurabh.artifact.audio.validation.ReviewProgress
import com.saurabh.artifact.audio.validation.ReviewTracker
import com.saurabh.artifact.audio.validation.ReviewValidator
import com.saurabh.artifact.data.local.ArtifactReviewEvidence
import com.saurabh.artifact.data.local.ReviewDao
import com.saurabh.artifact.domain.review.ReviewEvidence
import com.saurabh.artifact.domain.review.ReviewPolicy
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.repository.CommentUnlockRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authoritative service for review validation.
 * Unifies logic for Draft Publication and Comment Unlocking.
 * Upgraded with debounced persistence and policy-based validation.
 */
@Singleton
@OptIn(FlowPreview::class)
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

    private val persistenceTrigger = MutableSharedFlow<ReviewProgress>(extraBufferCapacity = 1)

    init {
        observePlayback()
        setupPersistence()
    }

    private fun setupPersistence() {
        scope.launch {
            persistenceTrigger
                .debounce(5000L) // Persist at most every 5 seconds during active playback
                .collect { progress ->
                    persistProgress(progress)
                }
        }
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
                        val progress = tracker.getProgress()
                        _currentProgress.value = progress
                        
                        // Buffer for debounced persistence
                        persistenceTrigger.emit(progress)
                        
                        // Check for completion
                        if (progress.isValidationMet) {
                            handleCompletion(progress)
                        }
                    }
                } else {
                    lastTickTime = 0L
                }
                delay(1000) // 1 second resolution for state updates
            }
        }

        // Observe State for End Events
        scope.launch {
            playbackSessionManager.playbackState.collect { state ->
                if (state == Player.STATE_ENDED) {
                    activeTracker?.onPlaybackEnded()
                    val progress = activeTracker?.getProgress()
                    _currentProgress.value = progress
                    progress?.let { if (it.isValidationMet) handleCompletion(it) }
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
        val dbEvidence = reviewDao.getEvidence(artifact.id)
        val domainEvidence = if (dbEvidence != null) {
            ReviewEvidence(
                artifactId = dbEvidence.artifactId,
                versionTag = dbEvidence.versionTag,
                durationMs = dbEvidence.durationMs,
                audioChecksum = dbEvidence.audioChecksum,
                coverage = java.util.BitSet.valueOf(dbEvidence.coverage),
                effortMap = dbEvidence.effortMap,
                furthestPositionMs = dbEvidence.furthestPositionMs,
                hasReachedEnd = dbEvidence.hasReachedEnd,
                lastUpdated = dbEvidence.lastUpdated
            )
        } else {
            ReviewEvidence(
                artifactId = artifact.id,
                versionTag = "v1", // Default version
                durationMs = artifact.durationMs,
                audioChecksum = artifact.checksum ?: ""
            )
        }

        activeTracker = DefaultReviewTracker(
            initialEvidence = domainEvidence,
            policy = ReviewPolicy(), // Policy can be injected or resolved per artifact type
            validator = validator
        )
        _currentProgress.value = activeTracker?.getProgress()
        lastTickTime = SystemClock.elapsedRealtime()
    }

    private suspend fun finalizeSession() {
        val progress = activeTracker?.getProgress() ?: return
        persistProgress(progress) // Immediate final persistence
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
            val evidence = progress.evidence
            reviewDao.insertEvidence(
                ArtifactReviewEvidence(
                    artifactId = evidence.artifactId,
                    versionTag = evidence.versionTag,
                    durationMs = evidence.durationMs,
                    audioChecksum = evidence.audioChecksum,
                    coverage = evidence.coverage.toByteArray(),
                    effortMap = evidence.effortMap,
                    furthestPositionMs = evidence.furthestPositionMs,
                    hasReachedEnd = evidence.hasReachedEnd,
                    lastUpdated = evidence.lastUpdated
                )
            )
        }
    }

    fun onSeekPerformed(targetMs: Long) {
        activeTracker?.onSeekPerformed(targetMs)
    }
}
