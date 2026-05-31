package com.saurabh.artifact.audio

import com.saurabh.artifact.audio.validation.ReviewResult
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the intentional review journey for drafts.
 * Delegates validation to the authoritative ReviewAuthorityService.
 */
@Singleton
class ReviewSessionManager @Inject constructor(
    private val playbackSessionManager: PlaybackSessionManager,
    private val reviewAuthorityService: ReviewAuthorityService,
    private val draftDao: DraftDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    val reviewProgress: StateFlow<ReviewState> = reviewAuthorityService.currentProgress
        .map { progress ->
            if (progress == null) ReviewState()
            else {
                val evidence = progress.evidence
                ReviewState(
                    artifactId = progress.artifactId,
                    durationMs = progress.durationMs,
                    furthestPositionMs = evidence.furthestPositionMs,
                    coveragePercent = progress.coveragePercent,
                    effortPercent = progress.effortPercent,
                    isThresholdMet = progress.isValidationMet,
                    isPlaybackEnded = progress.hasReachedEnd,
                    reviewResult = progress.reviewResult
                )
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), ReviewState())

    init {
        observeCompletion()
    }

    private fun observeCompletion() {
        scope.launch {
            reviewProgress.collect { state ->
                if (state.isThresholdMet && state.artifactId != null) {
                    markReviewComplete(state.artifactId)
                }
            }
        }
    }

    fun startReview(draftId: String) {
        scope.launch {
            val draft = draftDao.getDraftById(draftId) ?: return@launch
            
            val artifact = Artifact(
                id = draft.id,
                audioUrl = "file://${draft.localAudioPath}",
                title = draft.title ?: "Your Draft",
                durationMs = draft.durationMs,
                isDraft = true
            )

            playbackSessionManager.play(
                artifact = artifact, 
                owner = PlaybackSessionManager.InteractionOwner.REVIEW_PLAYER,
                initialPosition = draft.lastPlaybackPositionMs
            )
        }
    }

    fun startListening(artifact: Artifact) {
        playbackSessionManager.play(
            artifact = artifact,
            owner = PlaybackSessionManager.InteractionOwner.PUBLIC_PLAYER
        )
    }

    private suspend fun markReviewComplete(artifactId: String) {
        withContext(Dispatchers.IO) {
            val draft = draftDao.getDraftById(artifactId)
            if (draft != null && draft.draftState != ArtifactDraftState.REVIEW_COMPLETED) {
                draftDao.update(draft.copy(
                    status = draft.status.copy(lifecycle = ArtifactLifecycle.READY_TO_PUBLISH),
                    draftState = ArtifactDraftState.REVIEW_COMPLETED
                ))
            }
        }
    }

    fun stopReview() {
        playbackSessionManager.stop()
    }
}

data class ReviewState(
    val artifactId: String? = null,
    val durationMs: Long = 0L,
    val furthestPositionMs: Long = 0L,
    val coveragePercent: Float = 0f,
    val effortPercent: Float = 0f,
    val isThresholdMet: Boolean = false,
    val isPlaybackEnded: Boolean = false,
    val reviewResult: ReviewResult? = null
) {
    val progress: Float
        get() = if (durationMs > 0) furthestPositionMs.toFloat() / durationMs else 0f
}
