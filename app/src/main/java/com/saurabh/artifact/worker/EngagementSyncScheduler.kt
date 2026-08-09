package com.saurabh.artifact.worker

import android.content.Context
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngagementSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Schedules a synchronization task to upload pending engagement data.
     * Uses unique work to prevent redundant worker instances while ensuring
     * that at least one sync run eventually processes the latest changes.
     */
    fun scheduleSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<InteractionSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            syncRequest
        )
    }

    companion object {
        private const val TAG = "EngagementSync"
        private const val WORK_NAME = "InteractionSyncWorker" // Reusing same worker/queue for interactions
    }
}
