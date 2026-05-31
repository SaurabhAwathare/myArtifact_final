package com.saurabh.artifact.domain

import android.content.Context
import androidx.work.*
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.*
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
    private val draftRepository: com.saurabh.artifact.repository.DraftRepository,
    private val connectivityObserver: com.saurabh.artifact.util.ConnectivityObserver
) {
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    suspend fun startProcessing(draftId: String) = withContext(Dispatchers.IO) {
        val draft = draftRepository.getDraft(draftId) ?: return@withContext
        
        // Transition to Processing
        // Note: For now we'll keep using draftDao for status updates if it doesn't involve upload task
        // Actually, DraftRepository should probably handle all status updates eventually.
    }

    suspend fun markForReview(draftId: String) = withContext(Dispatchers.IO) {
        draftRepository.getDraft(draftId)?.let {
            // draftDao.update(it.copy(status = it.status.copy(lifecycle = ArtifactLifecycle.REVIEW_REQUIRED)))
            // Update to use Repository once implemented
        }
    }

    suspend fun startReview(draftId: String) = withContext(Dispatchers.IO) {
        // Just observing state here, but we could update if we had a REVIEWING lifecycle
    }

    suspend fun requestEmotionalConfirmation(draftId: String) = withContext(Dispatchers.IO) {
        // Emotional confirmation is part of the Review flow now
    }

    suspend fun requestPublishApproval(draftId: String) = withContext(Dispatchers.IO) {
        // Approval is implicit in READY_TO_PUBLISH
    }

    suspend fun approvePublishing(draftId: String) = withContext(Dispatchers.IO) {
        val draft = draftRepository.getDraft(draftId) ?: return@withContext
        
        // 1. Check Cooldown if applicable
        val now = System.currentTimeMillis()
        if (draft.cooldownExpiry != null && now < draft.cooldownExpiry) {
            return@withContext
        }

        // 2. Transition to READY_TO_PUBLISH + SyncStatus.Queued or SyncStatus.WaitingForNetwork
        val initialStatus = if (connectivityObserver.isOnline()) {
            SyncStatus.Queued
        } else {
            SyncStatus.WaitingForNetwork
        }
        
        draftRepository.prepareForPublishing(draftId, initialStatus)
        
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
            ExistingWorkPolicy.KEEP,
            publishingWork
        )
    }

    suspend fun retryPublishing(draftId: String) = withContext(Dispatchers.IO) {
        val draft = draftRepository.getDraft(draftId) ?: return@withContext
        if (draft.status.sync is SyncStatus.Failed) {
            draftRepository.updateUploadStatus(draftId, SyncStatus.Queued)
            enqueuePublishingWork(draftId)
        }
    }
}
