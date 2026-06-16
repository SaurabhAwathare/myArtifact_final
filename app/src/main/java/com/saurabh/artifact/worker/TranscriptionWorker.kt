package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.audio.LocalDraftManager
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
    private val localDraftManager: LocalDraftManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        val draft = recordingRepository.getDraft(draftId).getOrNull() ?: return@withContext Result.failure()
        val file = File(draft.localAudioPath)

        if (!file.exists()) {
            Log.e("TranscriptionWorker", "File not found: ${draft.localAudioPath}")
            updateSubState(draftId, null, "File not found")
            return@withContext Result.failure()
        }

        try {
            // IDEMPOTENCY CHECK: If transcript already exists and repository knows about it, skip
            if (draft.localTranscriptPath != null && File(draft.localTranscriptPath).exists()) {
                Log.d("TranscriptionWorker", "Idempotency Trigger: Transcript already exists. Skipping.")
                return@withContext Result.success()
            }

            updateSubState(draftId, com.saurabh.artifact.model.ProcessingStage.TRANSCRIBING)

            // 1. Prepare audio for processing with timeout
            Log.d("TranscriptionWorker", "Atmospheric Step: Listening quietly to your words...")
            
            // 2. Perform Transcription with timeout
            Log.d("TranscriptionWorker", "Starting transcription for: ${file.absolutePath}")
            val transcriptText = withTimeout(1.minutes) {
                performTranscription(file)
            }

            // Save transcript to file
            val transcriptFile = localDraftManager.createTranscriptFile(draftId)
            transcriptFile.writeText(transcriptText)
            val transcriptPath = transcriptFile.absolutePath

            // 3. Perform Emotional Analysis
            val emotionalTone = analyzeEmotionalTone()
            
            // 4. Perform Conversational Style Analysis
            val conversationStyle = analyzeConversationStyle()

            // 5. Update Repository with targeted update
            recordingRepository.updateTranscriptionResult(
                id = draftId,
                localTranscriptPath = transcriptPath,
                emotionalTone = emotionalTone,
                primaryStyle = conversationStyle
            )

            Log.d("TranscriptionWorker", "Transcription completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e("TranscriptionWorker", "Error during transcription: ${e.message}", e)
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

    private suspend fun performTranscription(file: File): String {
        Log.d("TranscriptionWorker", "Processing file: ${file.name}")
        delay(3.seconds) // Simulate work
        return "This is a placeholder transcript for the emotionally rich voice recording."
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
