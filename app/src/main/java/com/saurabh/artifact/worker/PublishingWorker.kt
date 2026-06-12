package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.saurabh.artifact.data.local.UploadOwner
import com.saurabh.artifact.data.local.UploadTaskDao
import com.saurabh.artifact.model.SyncStatus
import com.saurabh.artifact.repository.DraftRepository
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
    private val uploadTaskDao: UploadTaskDao
) : CoroutineWorker(appContext, workerParams) {

    private var startTime = System.currentTimeMillis()
    private var lastProgressUpdateTime = 0L

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        
        startTime = System.currentTimeMillis()

        // 0. Initialize Foreground Service for long-running upload
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w("PublishingWorker", "Could not set foreground info", e)
        }

        // 0.2 Acquire Ownership (with 10 min timeout threshold)
        val timeoutThreshold = System.currentTimeMillis() - 10 * 60 * 1000L
        val acquired = withContext(Dispatchers.IO) {
            uploadTaskDao.tryAcquireOwnership(draftId, UploadOwner.WORKER, timeoutThreshold)
        }

        if (!acquired) {
            Log.i("PublishingWorker", "Could not acquire ownership for $draftId (likely owned by SERVICE)")
            return@withContext Result.success() // Service is handling it
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
                NotificationHelper.showUploadSuccessNotification(appContext, title)
                Result.success()
            } else {
                handleFailure(draftId, result.exceptionOrNull() as Exception)
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
