package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.security.DatabaseEncryptionManager
import com.saurabh.artifact.domain.PublishingOrchestrator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decoupled recovery engine for interrupted recordings.
 * Triggered on app start to ensure service stays lean and durable.
 */
@HiltWorker
class RecoveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val recordingRepository: RecordingRepository,
    private val encryptionManager: DatabaseEncryptionManager,
    private val publishingOrchestrator: PublishingOrchestrator
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d("RecoveryWorker", "Starting automated recovery scan...")
            
            // Periodically refresh encryption metadata to ensure it uses the latest master key
            encryptionManager.refreshEncryptionMetadata()

            val recoveredResult = recordingRepository.recoverInterruptedDrafts()
            recoveredResult.onSuccess { recovered ->
                if (recovered.isNotEmpty()) {
                    Log.i("RecoveryWorker", "Successfully identified ${recovered.size} interrupted drafts. Evaluating recovery safeguards...")
                    recovered.forEach { draft ->
                        // Idempotency: Only restart if work is NOT already active
                        if (!publishingOrchestrator.isProcessingActive(draft.id)) {
                            Log.d("RecoveryWorker", "Triggering recovery for draft: ${draft.id}")
                            
                            // Mark attempt to ensure cooldown is respected
                            recordingRepository.markRecoveryAttempt(draft.id)
                            
                            // Resume the pipeline
                            publishingOrchestrator.startProcessing(draft.id)
                        } else {
                            Log.d("RecoveryWorker", "Recovery skipped for draft ${draft.id}: Processing already active.")
                        }
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("RecoveryWorker", "Recovery scan failed", e)
            Result.retry()
        }
    }
}
