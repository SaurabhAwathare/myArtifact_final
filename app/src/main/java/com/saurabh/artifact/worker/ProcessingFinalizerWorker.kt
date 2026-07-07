package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.RecordingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
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
    private val diagnosticLogger: DiagnosticLogger
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "PROCESSING_FINALIZATION_STARTED", mapOf(LogKeys.DRAFT_ID to draftId))
        
        try {
            // Targeted finalization update
            recordingRepository.finalizeProcessing(draftId)
            
            diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "PROCESSING_FINALIZATION_SUCCESS", mapOf(LogKeys.DRAFT_ID to draftId))
            Result.success()
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.WORKMANAGER, "PROCESSING_FINALIZATION_FAILED", mapOf(LogKeys.DRAFT_ID to draftId), e)
            Result.retry()
        }
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
