package com.saurabh.artifact.domain

import androidx.work.*
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.*
import com.saurabh.artifact.worker.PublishingWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PublishingOrchestrator @Inject constructor(
    private val draftRepository: com.saurabh.artifact.repository.DraftRepository,
    private val connectivityObserver: com.saurabh.artifact.util.ConnectivityObserver,
    private val workManager: WorkManager
) {

    suspend fun startProcessing(draftId: String) = withContext(Dispatchers.IO) {
        draftRepository.updateLifecycle(draftId, ArtifactLifecycle.PROCESSING)
    }

    suspend fun markForReview(draftId: String) = withContext(Dispatchers.IO) {
        draftRepository.updateLifecycle(draftId, ArtifactLifecycle.REVIEW_REQUIRED)
    }

    suspend fun startReview(draftId: String) = withContext(Dispatchers.IO) {
        // Just observing state here, but we could update if we had a REVIEWING lifecycle
    }

    suspend fun requestEmotionalConfirmation(draftId: String) = withContext(Dispatchers.IO) {
        // Transitional state for emotional confirmation
        draftRepository.updateProcessingStatus(draftId, ProcessingStatus.Active(ProcessingStage.SAFETY_CHECK))
    }

    suspend fun requestPublishApproval(draftId: String) = withContext(Dispatchers.IO) {
        // Approval is implicit in READY_TO_PUBLISH
    }

    suspend fun approvePublishing(draftId: String): PublishingResult = withContext(Dispatchers.IO) {
        val draft = draftRepository.getDraft(draftId) ?: return@withContext PublishingResult.FAILED
        
        // 0. Check if already publishing to avoid double enqueuing
        if (draft.status.lifecycle == ArtifactLifecycle.PUBLISHED) {
            return@withContext PublishingResult.ALREADY_IN_PROGRESS
        }

        // 1. Check Cooldown if applicable
        val now = System.currentTimeMillis()
        if (draft.cooldownExpiry != null && now < draft.cooldownExpiry) {
            return@withContext PublishingResult.FAILED
        }

        // 2. Transition to READY_TO_PUBLISH + SyncStatus.Queued or SyncStatus.WaitingForNetwork
        val isOnline = connectivityObserver.isOnline()
        val initialStatus = if (isOnline) {
            SyncStatus.Queued
        } else {
            SyncStatus.WaitingForNetwork
        }
        
        draftRepository.prepareForPublishing(draftId, initialStatus)
        
        // 3. Trigger Publishing Worker
        enqueuePublishingWork(draftId)

        if (isOnline) PublishingResult.UPLOAD_STARTED else PublishingResult.QUEUED_OFFLINE
    }

    private fun enqueuePublishingWork(draftId: String) {
        val inputData = workDataOf(PublishingWorker.KEY_DRAFT_ID to draftId)
        
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val publishingWork = OneTimeWorkRequestBuilder<PublishingWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag("publish_$draftId")
            .build()

        workManager.enqueueUniqueWork(
            "publish_$draftId",
            ExistingWorkPolicy.KEEP,
            publishingWork
        )
    }

    suspend fun retryPublishing(draftId: String) = withContext(Dispatchers.IO) {
        val draft = draftRepository.getDraft(draftId) ?: return@withContext
        if (draft.status.publication is SyncStatus.Failed) {
            draftRepository.updateUploadStatus(draftId, SyncStatus.Queued)
            enqueuePublishingWork(draftId)
        }
    }
}
