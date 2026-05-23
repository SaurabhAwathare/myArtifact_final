package com.saurabh.artifact.domain

import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.ArtifactDraftState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PublishManager @Inject constructor(
    private val draftDao: DraftDao,
    private val publishingOrchestrator: PublishingOrchestrator
) {
    /**
     * Attempts to initiate the publishing flow.
     * Enforces that review must be completed.
     */
    suspend fun prepareForPublishing(draftId: String) = withContext(Dispatchers.IO) {
        val draft = draftDao.getDraftById(draftId) ?: return@withContext
        
        if (draft.draftState != ArtifactDraftState.REVIEW_COMPLETED && 
            draft.draftState != ArtifactDraftState.REVIEWED) {
            // Enforcement: Cannot prepare for publish if not reviewed
            return@withContext
        }

        // Move to PENDING_APPROVAL or equivalent metadata stage
        publishingOrchestrator.requestPublishApproval(draftId)
    }

    suspend fun finalizePublish(draftId: String) = withContext(Dispatchers.IO) {
        publishingOrchestrator.approvePublishing(draftId)
    }
}
