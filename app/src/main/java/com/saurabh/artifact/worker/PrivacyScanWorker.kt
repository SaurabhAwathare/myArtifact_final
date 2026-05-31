package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.model.PiiType
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.service.SensitiveInfoScanner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

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
            updateState(draftId, com.saurabh.artifact.model.ProcessingStage.PRIVACY_SCANNING)
            
            val draft = draftDao.getDraftById(draftId) ?: return@withContext Result.failure()
            
            // Simulation of PII detection using the real scanner
            delay(1000)
            
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
            
            val sensitiveJson = Json.encodeToString(flaggedSegments)
            
            // Finalizing scan
            draftDao.update(draft.copy(
                sensitiveEntitiesJson = sensitiveJson,
                updatedAt = System.currentTimeMillis()
            ))
            
            Result.success()
        } catch (e: Exception) {
            Log.e("PrivacyScanWorker", "Error during privacy scan", e)
            updateState(draftId, null, "Privacy scan failed: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun updateState(id: String, stage: com.saurabh.artifact.model.ProcessingStage?, error: String? = null) {
        draftDao.getDraftById(id)?.let { draft ->
            val newProcessing = when {
                error != null -> com.saurabh.artifact.model.ProcessingStatus.Failed(error)
                stage != null -> com.saurabh.artifact.model.ProcessingStatus.Active(stage)
                else -> com.saurabh.artifact.model.ProcessingStatus.Idle
            }
            draftDao.update(draft.copy(
                status = draft.status.copy(processing = newProcessing),
                updatedAt = System.currentTimeMillis()
            ))
        }
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
