package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.domain.auth.SessionConstants
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.security.DatabaseEncryptionManager
import com.saurabh.artifact.domain.PublishingOrchestrator
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import dagger.Lazy
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
    private val publishingOrchestrator: PublishingOrchestrator,
    private val startupCoordinator: com.saurabh.artifact.startup.StartupCoordinator,
    private val diagnosticLogger: DiagnosticLogger
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // WORKER LOCK: Ensure database encryption is ready before proceeding
        startupCoordinator.awaitComponent(com.saurabh.artifact.startup.StartupComponent.DATABASE)

        diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "RECOVERY_SCAN_STARTED")
        try {
            // Periodically refresh encryption metadata to ensure it uses the latest master key
            encryptionManager.refreshEncryptionMetadata()

            val recoveredResult = recordingRepository.recoverInterruptedDrafts()
            recoveredResult.onSuccess { recovered ->
                if (recovered.isNotEmpty()) {
                    diagnosticLogger.info(DiagnosticCategory.RECORDING, "RECOVERY_IDENTIFIED_DRAFTS", mapOf("count" to recovered.size))
                    recovered.forEach { draft ->
                        // Idempotency: Only restart if work is NOT already active
                        if (!publishingOrchestrator.isProcessingActive(draft.id)) {
                            diagnosticLogger.info(DiagnosticCategory.RECORDING, "RECOVERY_TRIGGERING_PIPELINE", mapOf(LogKeys.DRAFT_ID to draft.id))
                            
                            // Mark attempt to ensure cooldown is respected
                            recordingRepository.markRecoveryAttempt(draft.id)
                            
                            // Resume the pipeline
                            publishingOrchestrator.startProcessing(draft.id)
                        } else {
                            diagnosticLogger.debug(DiagnosticCategory.RECORDING, "RECOVERY_SKIPPED_ALREADY_ACTIVE", mapOf(LogKeys.DRAFT_ID to draft.id))
                        }
                    }
                }
            }

            diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "RECOVERY_SCAN_SUCCESS")
            Result.success()
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.WORKMANAGER, "RECOVERY_SCAN_FAILED", throwable = e)
            Result.retry()
        } finally {
            // Signal that filesystem discovery has completed for this session
            startupCoordinator.emitReadiness(com.saurabh.artifact.startup.StartupComponent.FILESYSTEM_DISCOVERY)
        }
    }
}
