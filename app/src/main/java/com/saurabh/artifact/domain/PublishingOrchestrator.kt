package com.saurabh.artifact.domain

import android.content.Context
import android.util.Log
import androidx.work.*
import com.saurabh.artifact.audio.UploadService
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
    @param:ApplicationContext private val context: Context,
    private val draftRepository: com.saurabh.artifact.repository.DraftRepository,
    private val approvalRepository: com.saurabh.artifact.repository.PublishApprovalRepository,
    private val connectivityObserver: com.saurabh.artifact.util.ConnectivityObserver,
    private val workManager: WorkManager
) {

    suspend fun startProcessing(draftId: String) = withContext(Dispatchers.IO) {
        // Optimistic state update
        draftRepository.updateStatus(draftId) {
            it.copy(
                lifecycle = ArtifactLifecycle.PROCESSING,
                processing = ProcessingStatus.Active(ProcessingStage.TRANSCODING)
            )
        }

        val inputData = workDataOf("key_draft_id" to draftId)

        val transcodingWork = OneTimeWorkRequestBuilder<com.saurabh.artifact.worker.TranscodingWorker>()
            .setInputData(inputData)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        val normalizationWork = OneTimeWorkRequestBuilder<com.saurabh.artifact.worker.AudioNormalizationWorker>()
            .setInputData(inputData)
            .build()

        val waveformWork = OneTimeWorkRequestBuilder<com.saurabh.artifact.worker.WaveformWorker>()
            .setInputData(inputData)
            .build()

        val transcriptionWork = OneTimeWorkRequestBuilder<com.saurabh.artifact.worker.TranscriptionWorker>()
            .setInputData(inputData)
            .build()

        val privacyWork = OneTimeWorkRequestBuilder<com.saurabh.artifact.worker.PrivacyScanWorker>()
            .setInputData(inputData)
            .build()

        val safetyWork = OneTimeWorkRequestBuilder<com.saurabh.artifact.worker.SafetyAnalysisWorker>()
            .setInputData(inputData)
            .build()

        val finalStateWork = OneTimeWorkRequestBuilder<com.saurabh.artifact.worker.ProcessingFinalizerWorker>()
            .setInputData(inputData)
            .build()

        workManager.beginUniqueWork(
            "process_$draftId",
            ExistingWorkPolicy.REPLACE,
            transcodingWork
        )
        .then(normalizationWork)
        .then(waveformWork)
        .then(transcriptionWork)
        .then(privacyWork)
        .then(safetyWork)
        .then(finalStateWork)
        .enqueue()
    }

    suspend fun approveAndPublish(
        draftId: String,
        transcript: List<TranscriptSegment>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val freezeResult = approvalRepository.approveAndFreeze(draftId, transcript)
            if (freezeResult.isFailure) return@withContext freezeResult

            Log.i("PublishingOrchestrator", "Draft $draftId approved and frozen. Enqueuing publication.")
            
            val draft = draftRepository.getDraft(draftId).getOrThrow()

            // 0. Strict Validation: Review Required
            if (draft.status.lifecycle != ArtifactLifecycle.READY_TO_PUBLISH) {
                Log.e("PublishingOrchestrator", "Attempted to publish unreviewed draft: $draftId")
                return@withContext Result.failure(Exception("95% Review required before publishing."))
            }

            // 1. Check if already publishing to avoid double enqueuing
            if (draft.status.lifecycle == ArtifactLifecycle.PUBLISHED) {
                return@withContext Result.success(Unit)
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
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("PublishingOrchestrator", "Critical failure during approval/publish handoff", e)
            Result.failure(e)
        }
    }

    suspend fun approvePublishing(draftId: String): PublishingResult = withContext(Dispatchers.IO) {
        // Legacy entry point - redirected to internal logic if needed, but preferred is approveAndPublish
        val draft = draftRepository.getDraft(draftId).getOrNull() ?: return@withContext PublishingResult.FAILED
        
        // 0. Strict Validation: Review Required
        if (draft.status.lifecycle != ArtifactLifecycle.READY_TO_PUBLISH) {
            Log.e("PublishingOrchestrator", "Attempted to publish unreviewed draft via legacy route: $draftId")
            return@withContext PublishingResult.FAILED
        }

        // 0.2 Check if already publishing to avoid double enqueuing
        if (draft.status.lifecycle == ArtifactLifecycle.PUBLISHED) {
            return@withContext PublishingResult.ALREADY_IN_PROGRESS
        }

        // 1. Check Cooldown if applicable
        val now = System.currentTimeMillis()
        if (draft.updatedAt != 0L && (now - draft.updatedAt) < 1000) { // Using updatedAt as proxy since cooldownExpiry is missing
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
        // Analytics
        val bundle = android.os.Bundle().apply { putString("draft_id", draftId) }
        try {
             // Injected manually since it's a Singleton
        } catch (_: Exception) {}

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
        val draft = draftRepository.getDraft(draftId).getOrNull() ?: return@withContext
        if (draft.status.publication is SyncStatus.Failed) {
            draftRepository.updateUploadStatus(draftId, SyncStatus.Queued)
            enqueuePublishingWork(draftId)
        }
    }
}
