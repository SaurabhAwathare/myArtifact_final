package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.saurabh.artifact.repository.DraftRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodically scans for "stuck" or queued publications and re-triggers the PublishingWorker.
 * A publication is considered stuck if it's in READY_TO_PUBLISH state but hasn't been updated recently.
 */
@HiltWorker
class PublishingRecoveryWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val uploadTaskDao: com.saurabh.artifact.data.local.UploadTaskDao,
    private val workManager: WorkManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - TimeUnit.HOURS.toMillis(1)

        val pendingTasks = uploadTaskDao.getAllTasks().filter { 
            it.lastUpdated < oneHourAgo
        }

        if (pendingTasks.isEmpty()) {
            return Result.success()
        }

        Log.i("PublishingRecovery", "Found ${pendingTasks.size} stuck upload tasks. Re-triggering...")

        for (task in pendingTasks) {
            val inputData = workDataOf(PublishingWorker.KEY_DRAFT_ID to task.draftId)
            
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val publishingWork = OneTimeWorkRequestBuilder<PublishingWorker>()
                .setInputData(inputData)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("publish_${task.draftId}")
                .build()

            workManager.enqueueUniqueWork(
                "publish_${task.draftId}",
                ExistingWorkPolicy.KEEP, // Keep if already running
                publishingWork
            )
        }

        return Result.success()
    }
}
