package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.saurabh.artifact.util.WorkNames
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.domain.auth.SessionConstants
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
    private val workManager: WorkManager,
    private val diagnosticLogger: DiagnosticLogger
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "PUBLISHING_RECOVERY_STARTED")
        
        val now = System.currentTimeMillis()
        val oneHourAgo = now - TimeUnit.HOURS.toMillis(1)

        val pendingTasks = uploadTaskDao.getAllTasks().filter { 
            it.lastUpdated < oneHourAgo
        }

        if (pendingTasks.isEmpty()) {
            diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "PUBLISHING_RECOVERY_NO_STUCK_TASKS")
            return Result.success()
        }

        diagnosticLogger.info(DiagnosticCategory.PUBLISH, "PUBLISHING_RECOVERY_RETRIGGER", mapOf("count" to pendingTasks.size))

        for (task in pendingTasks) {
            diagnosticLogger.debug(DiagnosticCategory.PUBLISH, "PUBLISHING_RECOVERY_TASK", mapOf(LogKeys.DRAFT_ID to task.draftId))
            val inputData = workDataOf(PublishingWorker.KEY_DRAFT_ID to task.draftId)
            
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val publishingWork = OneTimeWorkRequestBuilder<PublishingWorker>()
                .setInputData(inputData)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(WorkNames.forPublishing(task.draftId))
                .addTag(SessionConstants.TAG_USER_SESSION_WORK)
                .build()

            workManager.enqueueUniqueWork(
                WorkNames.forPublishing(task.draftId),
                ExistingWorkPolicy.KEEP, // Keep if already running
                publishingWork
            )
        }

        return Result.success()
    }
}
