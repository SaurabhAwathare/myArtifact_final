package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.data.local.DraftDao
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

@HiltWorker
class PrivacyScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val draftDao: DraftDao,
    private val scanner: SensitiveInfoScanner
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        
        try {
            updateState(draftId, ProcessingStage.PRIVACY_SCANNING)
            
            val draft = draftDao.getDraftById(draftId) ?: return@withContext Result.failure()
            
            // Simulation of PII detection using the real scanner
            delay(1.seconds)
            
            val transcriptPath = draft.localTranscriptPath
            Log.d("PrivacyScanWorker", "Scanning transcript at: $transcriptPath")
            
            val flaggedSegments = if (transcriptPath != null) {
                val file = File(transcriptPath)
                if (file.exists()) {
                    val text = file.readText()
                    Log.d("PrivacyScanWorker", "Transcript content length: ${text.length}")
                    val segments = listOf(TranscriptSegment(text = text, startMs = 0, endMs = draft.durationMs, confidence = 1.0f))
                    scanner.scan(segments)
                } else {
                    Log.w("PrivacyScanWorker", "Transcript file missing: $transcriptPath")
                    emptyList()
                }
            } else {
                Log.w("PrivacyScanWorker", "No transcript path for draft: $draftId")
                emptyList()
            }
            
            val sensitiveJson = com.saurabh.artifact.util.SecureString.fromString(Json.encodeToString(flaggedSegments))
            
            // Finalizing scan with targeted update
            draftDao.updatePrivacyResult(draftId, sensitiveJson)
            
            Result.success()
        } catch (e: Exception) {
            Log.e("PrivacyScanWorker", "Error during privacy scan", e)
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
