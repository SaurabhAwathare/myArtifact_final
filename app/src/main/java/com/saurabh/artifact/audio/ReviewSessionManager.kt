package com.saurabh.artifact.audio

import android.util.Log
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactDraftState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the intentional review journey for a single draft.
 * Ensures the "95% rule" is followed before allowing publication.
 */
@Singleton
class ReviewSessionManager @Inject constructor(
    private val playbackSessionManager: PlaybackSessionManager,
    private val draftDao: DraftDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private val _reviewProgress = MutableStateFlow(ReviewState())
    val reviewProgress: StateFlow<ReviewState> = _reviewProgress.asStateFlow()

    private var currentArtifactId: String? = null
    private var trackingJob: Job? = null
    
    private val THRESHOLD_PERCENT = 0.95f
    private val SCRUB_TOLERANCE_MS = 2000L

    fun startReview(draftId: String) {
        if (currentArtifactId == draftId) return
        
        trackingJob?.cancel()
        currentArtifactId = draftId
        
        scope.launch {
            val draft = draftDao.getDraftById(draftId) ?: return@launch
            
            // Map Draft Entity to Artifact for Player
            val artifact = Artifact(
                id = draft.id,
                audioUrl = "file://${draft.localAudioPath}",
                title = draft.title ?: "Your Draft",
                duration = draft.durationMs,
                isDraft = true
            )

            _reviewProgress.value = ReviewState(
                artifactId = draftId,
                durationMs = draft.durationMs,
                furthestPositionMs = draft.maxReviewPositionMs,
                isThresholdMet = (draft.maxReviewPositionMs.toFloat() / draft.durationMs.coerceAtLeast(1)) >= THRESHOLD_PERCENT
            )

            playbackSessionManager.play(
                artifact = artifact, 
                owner = PlaybackSessionManager.InteractionOwner.REVIEW_PLAYER,
                initialPosition = draft.lastPlaybackPositionMs
            )
            
            startTracking()
        }
    }

    /**
     * Starts listening to a published artifact.
     * Comments are unlocked based on the 95% rule tracked here.
     */
    fun startListening(artifact: Artifact) {
        if (currentArtifactId == artifact.id) return

        trackingJob?.cancel()
        currentArtifactId = artifact.id

        _reviewProgress.value = ReviewState(
            artifactId = artifact.id,
            durationMs = artifact.duration
        )

        playbackSessionManager.play(
            artifact = artifact,
            owner = PlaybackSessionManager.InteractionOwner.PUBLIC_PLAYER
        )
        startTracking()
    }

    private fun startTracking() {
        trackingJob = scope.launch {
            playbackSessionManager.currentPosition.collect { position ->
                val artifactId = currentArtifactId ?: return@collect
                val state = _reviewProgress.value
                
                // Anti-scrubbing logic
                val updatedFurthest = if (position > state.furthestPositionMs) {
                    if (position <= state.furthestPositionMs + SCRUB_TOLERANCE_MS) {
                        position
                    } else {
                        state.furthestPositionMs
                    }
                } else {
                    state.furthestPositionMs
                }

                if (updatedFurthest != state.furthestPositionMs) {
                    val percentage = updatedFurthest.toFloat() / state.durationMs.coerceAtLeast(1)
                    val thresholdMet = percentage >= THRESHOLD_PERCENT
                    
                    _reviewProgress.update { 
                        it.copy(
                            furthestPositionMs = updatedFurthest,
                            isThresholdMet = thresholdMet || it.isThresholdMet
                        )
                    }

                    // Debounced persistence or just persist key milestones
                    if (thresholdMet && !state.isThresholdMet) {
                        markReviewComplete(artifactId)
                    }
                    
                    // Frequent persistence for recovery (Drafts only)
                    val draft = withContext(Dispatchers.IO) { draftDao.getDraftById(artifactId) }
                    if (draft != null) {
                        withContext(Dispatchers.IO) {
                            draftDao.updateReviewProgress(artifactId, updatedFurthest, "")
                            draftDao.updateLastPlaybackPosition(artifactId, position)
                        }
                    }
                }
            }
        }
    }

    private suspend fun markReviewComplete(artifactId: String) {
        withContext(Dispatchers.IO) {
            val draft = draftDao.getDraftById(artifactId)
            if (draft != null) {
                draftDao.updateDraftState(artifactId, ArtifactDraftState.REVIEWED)
            }
        }
    }

    fun stopReview() {
        trackingJob?.cancel()
        playbackSessionManager.stop()
        currentArtifactId = null
        _reviewProgress.value = ReviewState()
    }
}

data class ReviewState(
    val artifactId: String? = null,
    val durationMs: Long = 0L,
    val furthestPositionMs: Long = 0L,
    val isThresholdMet: Boolean = false
) {
    val progress: Float
        get() = if (durationMs > 0) furthestPositionMs.toFloat() / durationMs else 0f
}
