package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.saurabh.artifact.data.local.AcquisitionResult
import com.saurabh.artifact.data.local.UploadOwner
import com.saurabh.artifact.data.local.UploadTaskDao
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.SyncStatus
import com.saurabh.artifact.repository.DraftRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.util.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@HiltWorker
class PublishingWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val publishingManager: com.saurabh.artifact.domain.PublishingManager,
    private val draftRepository: DraftRepository,
    private val authRepository: AuthRepository,
    private val uploadTaskDao: UploadTaskDao,
    private val diagnosticLogger: DiagnosticLogger
) : CoroutineWorker(appContext, workerParams) {

    private var startTime = System.currentTimeMillis()
    private var lastProgressUpdateTime = 0L

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        diagnosticLogger.info(DiagnosticCategory.PUBLISH, "PUBLISHING_WORKER_STARTED", mapOf(LogKeys.DRAFT_ID to draftId))
        
        startTime = System.currentTimeMillis()

        // 0. Initialize Foreground Service for long-running upload
        try {
            setForeground(getForegroundInfo())
            diagnosticLogger.debug(DiagnosticCategory.WORKMANAGER, "PUBLISHING_FOREGROUND_SET", mapOf(LogKeys.DRAFT_ID to draftId))
        } catch (e: Exception) {
            diagnosticLogger.warn(DiagnosticCategory.WORKMANAGER, "PUBLISHING_FOREGROUND_FAILED", mapOf(LogKeys.DRAFT_ID to draftId), e)
        }

        // 0.2 Acquire Ownership (with 10 min timeout threshold)
        val timeoutThreshold = System.currentTimeMillis() - 10 * 60 * 1000L
        val acquisitionResult = withContext(Dispatchers.IO) {
            uploadTaskDao.tryAcquireOwnership(draftId, UploadOwner.WORKER, timeoutThreshold)
        }

        when (acquisitionResult) {
            AcquisitionResult.ACQUIRED -> {
                // Phase 4: Explicit Ownership Verification
                val draft = draftRepository.getDraft(draftId).getOrNull()
                val currentUserId = authRepository.currentUserId

                if (draft == null || currentUserId.isEmpty() || draft.userId != currentUserId) {
                    diagnosticLogger.error(
                        DiagnosticCategory.PUBLISH, 
                        "PUBLISHING_WORKER_OWNERSHIP_FAILED", 
                        mapOf(
                            LogKeys.DRAFT_ID to draftId, 
                            "draftOwner" to (draft?.userId ?: "null"), 
                            "activeUser" to currentUserId
                        )
                    )
                    return@withContext Result.failure()
                }
                diagnosticLogger.info(DiagnosticCategory.PUBLISH, "PUBLISHING_WORKER_OWNERSHIP_VERIFIED", mapOf(LogKeys.DRAFT_ID to draftId))
            }
            AcquisitionResult.LOCKED -> {
                diagnosticLogger.info(
                    DiagnosticCategory.PUBLISH, 
                    "PUBLISHING_OWNERSHIP_BLOCKED", 
                    mapOf(LogKeys.DRAFT_ID to draftId, "reason" to "Owned by other component")
                )
                return@withContext Result.retry() // Let WorkManager back off and try again if the other component fails
            }
            AcquisitionResult.MISSING -> {
                diagnosticLogger.info(
                    DiagnosticCategory.PUBLISH, 
                    "PUBLISHING_WORKER_SKIPPED", 
                    mapOf(LogKeys.DRAFT_ID to draftId, "reason" to "Task already completed or deleted")
                )
                return@withContext Result.success() // Terminal result - no more retries
            }
        }

        try {
            val draft = draftRepository.getDraft(draftId).getOrNull()
            val title = draft?.title ?: "Artifact"

            val result = publishingManager.performPublish(
                draftId = draftId,
                onProgress = { transferred, total, _ ->
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdateTime > 500L || transferred == total) {
                        lastProgressUpdateTime = now
                        updateNotificationIfNeeded(title, transferred, total, draftId)
                    }
                }
            )

            if (result.isSuccess) {
                diagnosticLogger.info(DiagnosticCategory.PUBLISH, "PUBLISHING_WORKER_SUCCESS", mapOf(LogKeys.DRAFT_ID to draftId))
                NotificationHelper.showUploadSuccessNotification(appContext, title)
                Result.success()
            } else {
                val exception = result.exceptionOrNull() as Exception
                diagnosticLogger.error(DiagnosticCategory.PUBLISH, "PUBLISHING_WORKER_FAILED", mapOf(LogKeys.DRAFT_ID to draftId), exception)
                handleFailure(draftId, exception)
            }
        } finally {
            withContext(Dispatchers.IO) {
                uploadTaskDao.releaseOwnership(draftId)
            }
        }
    }

    private suspend fun handleFailure(draftId: String, e: Exception): Result {
        val isPermanent = publishingManager.isPermanentError(e)

        return withContext(NonCancellable) {
            if (isPermanent) {
                draftRepository.updateUploadStatus(draftId, SyncStatus.Failed(e.message ?: "Permanent upload failure"))
                NotificationHelper.showUploadErrorNotification(appContext, "Permanent failure")
                Result.failure()
            } else {
                val isNetworkError = publishingManager.isNetworkError(e)
                val nextStatus = if (isNetworkError) SyncStatus.WaitingForNetwork else SyncStatus.Queued
                draftRepository.updateUploadStatus(draftId, nextStatus)
                Result.retry()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return NotificationHelper.getUploadForegroundInfo(
            appContext,
            "Artifact",
            0
        )
    }

    private fun updateNotificationIfNeeded(title: String, transferred: Long, total: Long, draftId: String) {
        val now = System.currentTimeMillis()
        val duration = now - startTime
        
        if (duration > 5000) {
            val progress = (transferred * 100 / total).toInt()
            NotificationHelper.updateUploadProgress(appContext, title, progress, draftId)
        }
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
