package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.SyncState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodically scans for "stuck" uploads and re-queues them.
 * An upload is considered stuck if it's in UPLOADING state but hasn't been updated recently.
 */
@HiltWorker
class SyncRecoveryWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val draftDao: DraftDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - TimeUnit.HOURS.toMillis(1)

        val stuckDrafts = draftDao.getAllDrafts().filter { 
            (it.syncState == SyncState.UPLOADING || it.syncState == SyncState.QUEUED) && 
            it.updatedAt < oneHourAgo
        }

        if (stuckDrafts.isEmpty()) {
            return Result.success()
        }

        Log.i("SyncRecoveryWorker", "Found ${stuckDrafts.size} stuck drafts. Re-queuing...")

        for (draft in stuckDrafts) {
            // Update state to RECOVERING to trigger a fresh attempt
            draftDao.update(draft.copy(
                status = draft.status.copy(sync = com.saurabh.artifact.model.SyncStatus.Recovering),
                syncState = SyncState.RECOVERING,
                updatedAt = now
            ))

            // Schedule the individual sync worker
            val syncRequest = OneTimeWorkRequestBuilder<DraftSyncWorker>()
                .setInputData(workDataOf("draft_id" to draft.id))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "sync_${draft.id}",
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )
        }

        return Result.success()
    }
}
