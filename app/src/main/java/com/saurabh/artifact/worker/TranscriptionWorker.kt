package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.RecordingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Background worker to handle audio transcription and emotional analysis.
 * Uses a local-first approach with cloud fallback potential.
 */
@Suppress("SameReturnValue", "SameReturnValue")
@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val recordingRepository: RecordingRepository,
    private val localDraftManager: LocalDraftManager,
    private val diagnosticLogger: DiagnosticLogger
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        diagnosticLogger.info(DiagnosticCategory.RECORDING, "TRANSCRIPT_GENERATION_STARTED", mapOf(LogKeys.DRAFT_ID to draftId))
        
        val draft = recordingRepository.getDraft(draftId).getOrNull() ?: return@withContext Result.failure()
        val file = File(draft.localAudioPath)

        if (!file.exists()) {
            diagnosticLogger.error(DiagnosticCategory.RECORDING, "TRANSCRIPTION_FILE_MISSING", mapOf(LogKeys.DRAFT_ID to draftId))
            updateSubState(draftId, null, "File not found")
            return@withContext Result.failure()
        }

        try {
            // IDEMPOTENCY CHECK: If transcript already exists and repository knows about it, skip
            if (draft.localTranscriptPath != null && File(draft.localTranscriptPath).exists()) {
                diagnosticLogger.info(DiagnosticCategory.RECORDING, "TRANSCRIPTION_SKIP_IDEMPOTENT", mapOf(LogKeys.DRAFT_ID to draftId))
                return@withContext Result.success()
            }

            updateSubState(draftId, com.saurabh.artifact.model.ProcessingStage.TRANSCRIBING)

            // 1. Prepare audio for processing with timeout
            diagnosticLogger.debug(DiagnosticCategory.RECORDING, "TRANSCRIPTION_PROCESSING", mapOf(LogKeys.DRAFT_ID to draftId))
            
            // 2. Perform Transcription with timeout
            val transcriptText = withTimeout(1.minutes) {
                performTranscription(file, draftId)
            }

            val segmentCount = runCatching {
                Json.decodeFromString<List<TranscriptSegment>>(transcriptText).size
            }.getOrDefault(0)

            diagnosticLogger.info(
                DiagnosticCategory.RECORDING,
                "TRANSCRIPT_GENERATION_COMPLETED",
                mapOf(
                    LogKeys.DRAFT_ID to draftId,
                    "segment_count" to segmentCount
                )
            )

            // Save transcript to file
            val transcriptFile = localDraftManager.createTranscriptFile(draftId)
            transcriptFile.writeText(transcriptText)
            val transcriptPath = transcriptFile.absolutePath

            // 3. Perform Emotional Analysis
            val emotionalTone = analyzeEmotionalTone()
            
            // 4. Perform Conversational Style Analysis
            val conversationStyle = analyzeConversationStyle()

            // 5. Update Repository with targeted update
            diagnosticLogger.info(DiagnosticCategory.RECORDING, "TRANSCRIPT_PERSIST_REQUESTED", mapOf(LogKeys.DRAFT_ID to draftId))
            recordingRepository.updateTranscriptionResult(
                id = draftId,
                localTranscriptPath = transcriptPath,
                transcriptJson = transcriptText,
                emotionalTone = emotionalTone,
                primaryStyle = conversationStyle
            )

            diagnosticLogger.info(DiagnosticCategory.RECORDING, "TRANSCRIPTION_SUCCESS", mapOf(LogKeys.DRAFT_ID to draftId))
            Result.success()
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.RECORDING, "TRANSCRIPTION_FAILED", mapOf(LogKeys.DRAFT_ID to draftId), e)
            updateSubState(draftId, null, "Transcription failed: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun updateSubState(id: String, stage: com.saurabh.artifact.model.ProcessingStage?, error: String? = null) {
        val newProcessing = when {
            error != null -> com.saurabh.artifact.model.ProcessingStatus.Failed
            stage != null -> com.saurabh.artifact.model.ProcessingStatus.Active(stage)
            else -> com.saurabh.artifact.model.ProcessingStatus.Idle
        }
        recordingRepository.updateProcessingStatus(id, newProcessing)
    }

    private suspend fun performTranscription(file: File, draftId: String): String {
        diagnosticLogger.debug(DiagnosticCategory.RECORDING, "TRANSCRIPTION_FILE_ANALYSIS", mapOf(LogKeys.DRAFT_ID to draftId, "fileName" to file.name))
        delay(3.seconds) // Simulate work
        val segments = listOf(
            TranscriptSegment(
                id = "placeholder",
                text = "This is a placeholder transcript for the emotionally rich voice recording.",
                confidence = 1.0f
            )
        )
        return Json.encodeToString(segments)
    }

    private fun analyzeEmotionalTone(): EmotionalTone {
        return EmotionalTone.REFLECTIVE
    }

    private fun analyzeConversationStyle(): ConversationStyle {
        // Placeholder: In a future iteration, this will use the transcript
        // or audio energy levels to categorize the style.
        return ConversationStyle.REFLECTIVE
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
