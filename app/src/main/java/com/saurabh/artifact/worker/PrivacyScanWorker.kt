package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DEPRECATED/INACTIVE: This worker was originally used for transcript-based privacy scanning.
 * Automatic transcription is currently disabled, making this worker redundant.
 * 
 * Scheduled for removal or repurposing for audio-based PII detection.
 */
@HiltWorker
class PrivacyScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val draftDao: DraftDao,
    private val diagnosticLogger: DiagnosticLogger
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        diagnosticLogger.info(DiagnosticCategory.SECURITY, "PRIVACY_SCAN_INACTIVE", mapOf(LogKeys.DRAFT_ID to draftId))
        
        try {
            // Immediately transition to Idle as the feature is legacy
            draftDao.updateProcessingStatus(draftId, ProcessingStatus.Idle)
            Result.success()
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.SECURITY, "PRIVACY_SCAN_CLEANUP_FAILED", mapOf(LogKeys.DRAFT_ID to draftId), e)
            Result.failure()
        }
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
