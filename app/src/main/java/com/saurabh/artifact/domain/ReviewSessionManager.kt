package com.saurabh.artifact.domain

import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.ArtifactDraftState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReviewSessionManager @Inject constructor(
    private val draftDao: DraftDao
) {
    /**
     * Updates the review progress for a draft.
     * @param draftId The ID of the draft.
     * @param currentPositionMs Current playback position.
     * @param totalDurationMs Total duration of the audio.
     */
    suspend fun updateProgress(
        draftId: String,
        currentPositionMs: Long,
        totalDurationMs: Long
    ) = withContext(Dispatchers.IO) {
        val draft = draftDao.getDraftById(draftId) ?: return@withContext
        
        val newMax = maxOf(draft.maxReviewPositionMs, currentPositionMs)
        
        // Simple 95% threshold logic for MVP
        // In production, we'd use a bitmask for actual coverage tracking
        val progressPercent = if (totalDurationMs > 0) (newMax.toFloat() / totalDurationMs) else 0f
        
        draftDao.updateReviewProgress(draftId, newMax, draft.reviewCoverageBitmask ?: "")
        draftDao.updateLastPlaybackPosition(draftId, currentPositionMs)
        
        if (progressPercent >= 0.95f && draft.draftState != ArtifactDraftState.REVIEW_COMPLETED) {
            draftDao.updateDraftState(draftId, ArtifactDraftState.REVIEW_COMPLETED)
        } else if (draft.draftState == ArtifactDraftState.READY_TO_REVIEW) {
            draftDao.updateDraftState(draftId, ArtifactDraftState.REVIEWING)
        }
    }

    suspend fun resetReview(draftId: String) = withContext(Dispatchers.IO) {
        draftDao.updateReviewProgress(draftId, 0L, "")
        draftDao.updateDraftState(draftId, ArtifactDraftState.READY_TO_REVIEW)
    }
}
