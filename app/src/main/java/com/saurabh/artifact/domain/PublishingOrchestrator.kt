package com.saurabh.artifact.domain

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.guava.await
import com.saurabh.artifact.audio.UploadService
import com.saurabh.artifact.util.WorkNames
import com.saurabh.artifact.model.*
import com.saurabh.artifact.security.UploadGuard
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.domain.auth.SessionConstants
import com.saurabh.artifact.worker.PublishingWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PublishingOrchestrator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val draftRepository: com.saurabh.artifact.repository.DraftRepository,
    private val approvalRepository: com.saurabh.artifact.repository.PublishApprovalRepository,
    private val connectivityObserver: com.saurabh.artifact.util.ConnectivityObserver,
    private val uploadGuard: UploadGuard,
    private val authRepository: AuthRepository,
    private val workManager: WorkManager
) {

    suspend fun startProcessing(draftId: String) = withContext(Dispatchers.IO) {
        // Optimistic state update
        draftRepository.updateDraft(draftId) {
            it.copy(
                lifecycle = ArtifactLifecycle.PROCESSING,
                status = it.status.copy(
                    processing = ProcessingStatus.Active(ProcessingStage.TRANSCODING)
                )
            )
        }

        val inputData = workDataOf("key_draft_id" to draftId)

        val transcodingWork = OneTimeWorkRequestBuilder<com.saurabh.artifact.worker.TranscodingWorker>()
            .setInputData(inputData)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(SessionConstants.TAG_USER_SESSION_WORK)
            .build()

        val normalizationWork = OneTimeWorkRequestBuilder<com.saurabh.artifact.worker.AudioNormalizationWorker>()
            .setInputData(inputData)
            .addTag(SessionConstants.TAG_USER_SESSION_WORK)
            .build()

        val waveformWork = OneTimeWorkRequestBuilder<com.saurabh.artifact.worker.WaveformWorker>()
            .setInputData(inputData)
            .addTag(SessionConstants.TAG_USER_SESSION_WORK)
            .build()

        val finalStateWork = OneTimeWorkRequestBuilder<com.saurabh.artifact.worker.ProcessingFinalizerWorker>()
            .setInputData(inputData)
            .addTag(SessionConstants.TAG_USER_SESSION_WORK)
            .build()

        workManager.beginUniqueWork(
            WorkNames.forProcessing(draftId),
            ExistingWorkPolicy.REPLACE,
            transcodingWork
        )
        .then(normalizationWork)
        .then(waveformWork)
        .then(finalStateWork)
        .enqueue()
    }

    /**
     * Checks if a processing chain for the given draft is currently active.
     */
    suspend fun isProcessingActive(draftId: String): Boolean = withContext(Dispatchers.IO) {
        val workInfos = workManager.getWorkInfosForUniqueWork(WorkNames.forProcessing(draftId)).await()
        workInfos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
    }


    suspend fun approvePublishing(draftId: String): Result<PublishingResult> = withContext(Dispatchers.IO) {
        val draft = draftRepository.getDraft(draftId).getOrNull() ?: return@withContext Result.failure(Exception("Draft not found"))

        // 0. Security Validation: Ensure tokens are present and valid
        val userId = authRepository.currentUserId
        if (!uploadGuard.validateApproval(draft, userId)) {
            Log.i("PublishingOrchestrator", "Security validation failed or missing. Re-approving.")
            val reApproveResult = approvalRepository.approveAndFreezeAuto(draftId)
            if (reApproveResult.isFailure) {
                Log.e("PublishingOrchestrator", "Failed to auto-approve draft.")
                return@withContext Result.failure(reApproveResult.exceptionOrNull() ?: Exception("Auto-approval failed"))
            }
        }
        
        // 0.1 Strict Validation: Review Required
        if (draft.lifecycle != ArtifactLifecycle.READY_TO_PUBLISH) {
            Log.e("PublishingOrchestrator", "Attempted to publish unreviewed draft via legacy route.")
            return@withContext Result.failure(Exception("Review required before publishing."))
        }

        // 1. Check if already publishing to avoid double enqueuing
        if (draft.lifecycle == ArtifactLifecycle.PUBLISHED) {
            return@withContext Result.success(PublishingResult.ALREADY_IN_PROGRESS)
        }

        // 2. Transition to READY_TO_PUBLISH + SyncStatus.Queued or SyncStatus.WaitingForNetwork
        val isOnline = connectivityObserver.isOnline()
        val initialStatus = if (isOnline) {
            SyncStatus.Queued
        } else {
            SyncStatus.WaitingForNetwork
        }
        
        draftRepository.prepareForPublishing(draftId, initialStatus)
        
        // 3. Trigger Publishing Hybrid Solution
        // Immediate Start via Service
        UploadService.start(context, draftId)
        
        // Trigger Publishing Worker as fallback
        enqueuePublishingWork(draftId)

        val result = if (isOnline) PublishingResult.UPLOAD_STARTED else PublishingResult.QUEUED_OFFLINE
        Result.success(result)
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
            .addTag(WorkNames.forPublishing(draftId))
            .addTag(SessionConstants.TAG_USER_SESSION_WORK)
            .build()

        workManager.enqueueUniqueWork(
            WorkNames.forPublishing(draftId),
            ExistingWorkPolicy.KEEP,
            publishingWork
        )
        Log.d("PUBLISH_TRACE", "Publishing worker enqueued.")
    }

    suspend fun retryPublishing(draftId: String) = withContext(Dispatchers.IO) {
        val draft = draftRepository.getDraft(draftId).getOrNull() ?: return@withContext

        // 0. Security Validation: Regenerate tokens if validation fails
        val userId = authRepository.currentUserId
        if (!uploadGuard.validateApproval(draft, userId)) {
            Log.i("PublishingOrchestrator", "Retrying draft with invalid security state. Re-approving.")
            approvalRepository.approveAndFreezeAuto(draftId)
        }

        if (draft.status.publication is SyncStatus.Failed) {
            draftRepository.updateUploadStatus(draftId, SyncStatus.Queued)
            enqueuePublishingWork(draftId)
        }
    }
}
