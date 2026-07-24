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
import com.saurabh.artifact.service.SensitiveInfoScanner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * DEPRECATED/INACTIVE: This worker was originally used for transcript-based privacy scanning.
 * It is currently removed from the active processing pipeline as automatic transcription is disabled.
 * 
 * Reserved for future use: Potential audio-based PII detection or other safety analysis.
 */
@HiltWorker
class PrivacyScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val draftDao: DraftDao,
    private val scanner: SensitiveInfoScanner,
    private val diagnosticLogger: DiagnosticLogger
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        diagnosticLogger.info(DiagnosticCategory.SECURITY, "PRIVACY_SCAN_STARTED", mapOf(LogKeys.DRAFT_ID to draftId))
        
        try {
            updateState(draftId, ProcessingStage.PRIVACY_SCANNING)
            
            val draft = draftDao.getDraftById(draftId) ?: return@withContext Result.failure()
            
            // Simulation of PII detection using the real scanner
            delay(1.seconds)
            
            val transcriptPath = draft.localTranscriptPath
            diagnosticLogger.debug(DiagnosticCategory.SECURITY, "PRIVACY_SCAN_TRANSCRIPT", mapOf(LogKeys.DRAFT_ID to draftId))
            
            // Phase 1: Gracefully handle missing transcript as transcription is now optional.
            val flaggedSegments = if (transcriptPath != null) {
                val file = File(transcriptPath)
                if (file.exists()) {
                    val text = file.readText()
                    diagnosticLogger.debug(DiagnosticCategory.SECURITY, "PRIVACY_SCAN_CONTENT_ANALYSIS", mapOf(LogKeys.DRAFT_ID to draftId, "textLength" to text.length))
                    val segments = listOf(TranscriptSegment(text = text, startMs = 0, endMs = draft.durationMs, confidence = 1.0f))
                    scanner.scan(segments)
                } else {
                    diagnosticLogger.warn(DiagnosticCategory.SECURITY, "PRIVACY_SCAN_TRANSCRIPT_MISSING", mapOf(LogKeys.DRAFT_ID to draftId))
                    emptyList()
                }
            } else {
                diagnosticLogger.warn(DiagnosticCategory.SECURITY, "PRIVACY_SCAN_PATH_NULL", mapOf(LogKeys.DRAFT_ID to draftId))
                emptyList()
            }
            
            val sensitiveJson = com.saurabh.artifact.util.SecureString.fromString(Json.encodeToString(flaggedSegments))
            
            // Finalizing scan with targeted update
            draftDao.updatePrivacyResult(draftId, sensitiveJson)
            
            diagnosticLogger.info(DiagnosticCategory.SECURITY, "PRIVACY_SCAN_SUCCESS", mapOf(LogKeys.DRAFT_ID to draftId, "flaggedCount" to flaggedSegments.size))
            Result.success()
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.SECURITY, "PRIVACY_SCAN_FAILED", mapOf(LogKeys.DRAFT_ID to draftId), e)
            updateState(draftId, null, "Privacy scan failed: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun updateState(id: String, stage: ProcessingStage?, error: String? = null) {
        val newProcessing = when {
            error != null -> ProcessingStatus.Failed
            stage != null -> ProcessingStatus.Active(stage)
            else -> ProcessingStatus.Idle
        }
        draftDao.updateProcessingStatus(id, newProcessing)
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
