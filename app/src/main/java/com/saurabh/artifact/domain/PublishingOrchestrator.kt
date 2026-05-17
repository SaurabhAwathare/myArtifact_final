package com.saurabh.artifact.domain

import android.content.Context
import androidx.work.*
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.worker.PublishingWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PublishingOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val draftDao: DraftDao
) {
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    suspend fun startProcessing(draftId: String) = withContext(Dispatchers.IO) {
        val draft = draftDao.getDraftById(draftId) ?: return@withContext
        
        // Transition to Processing
        draftDao.updateDraftState(draftId, ArtifactDraftState.PROCESSING)
        
        // In a real app, we'd chain processing workers here.
        // For this architecture, we'll assume processing completes and move to READY_TO_REVIEW
    }

    suspend fun markForReview(draftId: String) = withContext(Dispatchers.IO) {
        draftDao.updateDraftState(draftId, ArtifactDraftState.READY_TO_REVIEW)
    }

    suspend fun startReview(draftId: String) = withContext(Dispatchers.IO) {
        draftDao.updateDraftState(draftId, ArtifactDraftState.REVIEWING)
    }

    suspend fun requestEmotionalConfirmation(draftId: String) = withContext(Dispatchers.IO) {
        draftDao.updateDraftState(draftId, ArtifactDraftState.EMOTIONAL_CONFIRMATION)
    }

    suspend fun requestPublishApproval(draftId: String) = withContext(Dispatchers.IO) {
        draftDao.updateDraftState(draftId, ArtifactDraftState.PENDING_APPROVAL)
    }

    suspend fun approvePublishing(draftId: String) = withContext(Dispatchers.IO) {
        val draft = draftDao.getDraftById(draftId) ?: return@withContext
        
        // 1. Check Cooldown if applicable
        val now = System.currentTimeMillis()
        if (draft.cooldownExpiry != null && now < draft.cooldownExpiry) {
            draftDao.updateDraftState(draftId, ArtifactDraftState.ERROR)
            return@withContext
        }

        // 2. Transition to APPROVED_FOR_PUBLISH
        draftDao.updateDraftState(draftId, ArtifactDraftState.APPROVED_FOR_PUBLISH)
        
        // 3. Trigger Publishing Worker
        enqueuePublishingWork(draftId)
    }

    private fun enqueuePublishingWork(draftId: String) {
        val inputData = workDataOf(PublishingWorker.KEY_DRAFT_ID to draftId)
        
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val publishingWork = OneTimeWorkRequestBuilder<PublishingWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag("publish_$draftId")
            .build()

        workManager.enqueueUniqueWork(
            "publish_$draftId",
            ExistingWorkPolicy.REPLACE,
            publishingWork
        )
    }

    suspend fun retryPublishing(draftId: String) = withContext(Dispatchers.IO) {
        val draft = draftDao.getDraftById(draftId) ?: return@withContext
        if (draft.draftState == ArtifactDraftState.ERROR) {
            draftDao.updateDraftState(draftId, ArtifactDraftState.APPROVED_FOR_PUBLISH)
            enqueuePublishingWork(draftId)
        }
    }
}
