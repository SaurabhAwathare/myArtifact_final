package com.saurabh.artifact.audio

import com.saurabh.artifact.audio.validation.ReviewResult
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.mapper.DraftToArtifactMapper
import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.domain.review.publishing.PublishingReviewPolicy
import com.saurabh.artifact.domain.review.publishing.PublishingReviewValidator
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.UserRepository
import dagger.Lazy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages the intentional review journey for drafts.
 * Delegates validation to the authoritative ReviewAuthorityService but uses
 * PublishingReviewPolicy for its own state calculations.
 */
@Singleton
class ReviewSessionManager @Inject constructor(
    private val playbackSessionManager: PlaybackSessionManager,
    reviewAuthorityService: ReviewAuthorityService,
    private val draftDao: Lazy<DraftDao>,
    private val publishingValidator: PublishingReviewValidator,
    private val publishingPolicy: PublishingReviewPolicy,
    private val draftMapper: DraftToArtifactMapper,
    private val userRepository: UserRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // @Inject lateinit var analytics: com.google.firebase.analytics.FirebaseAnalytics
    
    val reviewProgress: StateFlow<ReviewState> = reviewAuthorityService.currentProgress
        .map { progress ->
            if (progress == null) ReviewState()
            else {
                val evidence = progress.evidence
                
                // DECISION: Use the dedicated publishing validator for draft progress
                val validationResult = publishingValidator.validate(evidence, publishingPolicy)
                
                ReviewState(
                    artifactId = progress.artifactId,
                    durationMs = progress.durationMs,
                    furthestPositionMs = evidence.furthestPositionMs,
                    coveragePercent = progress.coveragePercent,
                    isThresholdMet = validationResult.isValid, // Use policy-driven result
                    isPlaybackEnded = progress.hasReachedEnd,
                    reviewResult = validationResult
                )
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), ReviewState())

    init {
        observeCompletion()
    }

    private fun observeCompletion() {
        scope.launch {
            @OptIn(FlowPreview::class)
            reviewProgress
                .sample(1000.milliseconds)
                .collect { state ->
                    state.artifactId?.let { id ->
                        updatePersistedProgress(id, state.coveragePercent)
                    }
                }
        }

        scope.launch {
            reviewProgress
                .map { it.artifactId to it.isThresholdMet }
                .distinctUntilChanged()
                .collect { (artifactId, isThresholdMet) ->
                    if (isThresholdMet && artifactId != null) {
                        ArtifactLogger.i(DiagnosticCategory.REVIEW, "REVIEW_THRESHOLD_MET", mapOf("artifactId" to artifactId))
                        trackReviewCompleted(artifactId)
                        markReviewComplete(artifactId)
                    }
                }
        }
    }

    private fun trackReviewCompleted(artifactId: String) {
        ArtifactLogger.d(DiagnosticCategory.REVIEW, "REVIEW_TRACK_COMPLETED", mapOf("artifactId" to artifactId))
    }

    private fun updatePersistedProgress(artifactId: String, progress: Float) {
        scope.launch(Dispatchers.IO) {
            val draft = draftDao.get().internalGetDraftByIdAgnostic(artifactId) ?: return@launch
            draftDao.get().updateReviewProgress(draft.id, draft.userId, progress)
        }
    }

    fun startReview(
        draftId: String, 
        source: com.saurabh.artifact.model.PlaybackSource = com.saurabh.artifact.model.PlaybackSource.REVIEW_DRAFT
    ) {
        if (reviewProgress.value.artifactId == draftId && playbackSessionManager.isPlaying.value) {
            playbackSessionManager.updateActivePlaybackContext(source)
            return
        }

        ArtifactLogger.i(DiagnosticCategory.REVIEW, "REVIEW_STARTED", mapOf("draftId" to draftId))
        scope.launch {
            val draft = draftDao.get().internalGetDraftByIdAgnostic(draftId) ?: return@launch
            
            // Build author snapshot for the draft playback
            val currentUser = userRepository.getCachedProfile()
            val author = if (currentUser != null) {
                AuthorSnapshot.fromUser(currentUser)
            } else {
                AuthorSnapshot(name = "Your Draft")
            }

            val artifact = draftMapper.map(
                draft = draft,
                author = author,
                fallbackTitle = "Your Draft"
            )

            playbackSessionManager.play(
                artifact = artifact, 
                playbackType = PlaybackType.DRAFT_PREVIEW,
                source = source
            )
        }
    }


    private suspend fun markReviewComplete(artifactId: String) {
        withContext(Dispatchers.IO) {
            val draft = draftDao.get().internalGetDraftByIdAgnostic(artifactId) ?: return@withContext
            
            // Idempotency guard: If already marked complete or in a post-review state, skip
            val isPostReview = when (draft.lifecycle) {
                ArtifactLifecycle.METADATA_REQUIRED,
                ArtifactLifecycle.READY_TO_PUBLISH,
                ArtifactLifecycle.PUBLISHED,
                ArtifactLifecycle.DELETING,
                ArtifactLifecycle.DELETED -> true
                ArtifactLifecycle.RECORDING,
                ArtifactLifecycle.PROCESSING,
                ArtifactLifecycle.REVIEW_REQUIRED -> false
            }

            if (draft.reviewCompleted && isPostReview) {
                ArtifactLogger.d(DiagnosticCategory.REVIEW, "REVIEW_ALREADY_COMPLETE_SKIPPING", mapOf("artifactId" to artifactId))
                return@withContext
            }

            ArtifactLogger.i(DiagnosticCategory.REVIEW, "REVIEW_MARKING_COMPLETE", mapOf(
                "artifactId" to artifactId,
                "fromLifecycle" to draft.lifecycle.name
            ))
            draftDao.get().markReviewCompletePartial(draft.id, draft.userId)
        }
    }
}

data class ReviewState(
    val artifactId: String? = null,
    val durationMs: Long = 0L,
    val furthestPositionMs: Long = 0L,
    val coveragePercent: Float = 0f,
    val isThresholdMet: Boolean = false,
    val isPlaybackEnded: Boolean = false,
    val reviewResult: ReviewResult? = null
) {
    val progress: Float
        get() = if (durationMs > 0) furthestPositionMs.toFloat() / durationMs else 0f
}
