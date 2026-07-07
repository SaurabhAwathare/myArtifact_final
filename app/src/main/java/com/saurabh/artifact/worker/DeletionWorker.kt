package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.audio.DraftDeletionManager
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider

/**
 * Retries failed draft deletions with exponential backoff.
 * Delegated to DraftDeletionManager to ensure authoritative logic.
 */
@HiltWorker
class DeletionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val deletionManagerProvider: Provider<DraftDeletionManager>,
    private val diagnosticLogger: DiagnosticLogger
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return Result.failure()
        
        diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "DELETION_RETRY_STARTED", mapOf(LogKeys.DRAFT_ID to draftId))
        
        return try {
            deletionManagerProvider.get().deleteDraft(draftId)
            diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "DELETION_RETRY_SUCCESS", mapOf(LogKeys.DRAFT_ID to draftId))
            Result.success()
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.WORKMANAGER, "DELETION_RETRY_FAILED", mapOf(LogKeys.DRAFT_ID to draftId), e)
            Result.retry()
        }
    }

    companion object {
        const val KEY_DRAFT_ID = "draft_id"
    }
}
