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
    private val draftDao: DraftDao,
    private val connectivityObserver: com.saurabh.artifact.util.ConnectivityObserver
) {
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    suspend fun startProcessing(draftId: String) = withContext(Dispatchers.IO) {
        val draft = draftDao.getDraftById(draftId) ?: return@withContext
        
        // Transition to Processing
        draftDao.update(draft.copy(
            status = draft.status.copy(
                lifecycle = ArtifactLifecycle.PROCESSING,
                processing = ProcessingStatus.Active(ProcessingStage.TRANSCODING)
            )
        ))
        
        // In a real app, we'd chain processing workers here.
        // For this architecture, we'll assume processing completes and move to REVIEW_REQUIRED
    }

    suspend fun markForReview(draftId: String) = withContext(Dispatchers.IO) {
        draftDao.getDraftById(draftId)?.let {
            draftDao.update(it.copy(status = it.status.copy(lifecycle = ArtifactLifecycle.REVIEW_REQUIRED)))
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
        val draft = draftDao.getDraftById(draftId) ?: return@withContext
        
        // 1. Check Cooldown if applicable
        val now = System.currentTimeMillis()
        if (draft.cooldownExpiry != null && now < draft.cooldownExpiry) {
            return@withContext
        }

        // 2. Transition to READY_TO_PUBLISH + SyncStatus.Queued or SyncStatus.WaitingForNetwork
        val newSyncStatus = if (connectivityObserver.isOnline()) {
            SyncStatus.Queued
        } else {
            SyncStatus.WaitingForNetwork
        }
        
        draftDao.update(draft.copy(
            status = draft.status.copy(
                lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
                sync = newSyncStatus
            )
        ))
        
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
        if (draft.status.sync is SyncStatus.Failed) {
            draftDao.update(draft.copy(
                status = draft.status.copy(sync = SyncStatus.Queued)
            ))
            enqueuePublishingWork(draftId)
        }
    }
}
