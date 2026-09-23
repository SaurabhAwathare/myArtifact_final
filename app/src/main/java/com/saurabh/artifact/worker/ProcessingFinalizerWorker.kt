package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.RecordingRepository
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finalizes the local processing chain and transitions the draft to REVIEW_REQUIRED.
 */
@HiltWorker
class ProcessingFinalizerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val recordingRepository: RecordingRepository,
    private val draftDao: Lazy<DraftDao>,
    private val authRepository: AuthRepository,
    private val startupCoordinator: com.saurabh.artifact.startup.StartupCoordinator,
    private val diagnosticLogger: DiagnosticLogger
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // WORKER LOCK: Ensure database encryption is ready before proceeding
        startupCoordinator.awaitComponent(com.saurabh.artifact.startup.StartupComponent.DATABASE)

        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        val userId = authRepository.currentUserId
        
        if (userId.isEmpty()) return@withContext Result.failure()

        diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "PROCESSING_FINALIZATION_STARTED", mapOf(LogKeys.DRAFT_ID to draftId))
        
        try {
            // 1. Verify draft exists; if draft was deleted during processing, exit cleanly
            draftDao.get().getDraftById(draftId, userId) ?: return@withContext Result.success()

            // 2. Targeted finalization update (sets ProcessingStatus.Idle while preserving current lifecycle)
            recordingRepository.finalizeProcessing(draftId).getOrThrow()

            diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "PROCESSING_FINALIZATION_SUCCESS", mapOf(LogKeys.DRAFT_ID to draftId))
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            diagnosticLogger.error(DiagnosticCategory.WORKMANAGER, "PROCESSING_FINALIZATION_FAILED", mapOf(LogKeys.DRAFT_ID to draftId), e)
            Result.retry()
        }
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
