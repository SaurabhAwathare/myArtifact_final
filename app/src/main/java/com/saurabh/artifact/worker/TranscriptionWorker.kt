package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.util.EncryptedStorageManager
import com.saurabh.artifact.util.StorageManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Background worker to handle audio transcription and emotional analysis.
 * Uses a local-first approach with cloud fallback potential.
 */
@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val recordingRepository: RecordingRepository,
    private val storageManager: StorageManager,
    private val encryptedStorageManager: EncryptedStorageManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        val draft = recordingRepository.getDraft(draftId) ?: return@withContext Result.failure()
        val file = File(draft.localAudioPath)

        if (!file.exists()) {
            Log.e("TranscriptionWorker", "File not found: ${draft.localAudioPath}")
            recordingRepository.updateDraft(draft.copy(draftState = ArtifactDraftState.ERROR))
            return@withContext Result.failure()
        }

        var tempDecryptedFile: File? = null
        try {
            // IDEMPOTENCY CHECK: If transcript already exists and repository knows about it, skip
            if (draft.localTranscriptPath != null && File(draft.localTranscriptPath).exists()) {
                Log.d("TranscriptionWorker", "Idempotency Trigger: Transcript already exists. Skipping.")
                return@withContext Result.success()
            }

            recordingRepository.updateDraft(draft.copy(draftState = ArtifactDraftState.TRANSCRIBING))

            // 1. Decrypt audio for processing with timeout
            Log.d("TranscriptionWorker", "Atmospheric Step: Listening quietly to your words...")
            val audioFile = withTimeout(30000) {
                if (draft.isEncrypted) {
                    val tempFile = File(applicationContext.cacheDir, "transcribe_${System.currentTimeMillis()}.m4a")
                    encryptedStorageManager.getEncryptedInputStream(file).use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempDecryptedFile = tempFile
                    tempFile
                } else {
                    file
                }
            }
            
            // 2. Perform Transcription with timeout
            Log.d("TranscriptionWorker", "Starting transcription for: ${audioFile.absolutePath}")
            val transcriptText = withTimeout(60000) {
                performTranscription(audioFile)
            }

            // Save transcript to file
            val transcriptFile = File(applicationContext.filesDir, "transcript_$draftId.txt")
            transcriptFile.writeText(transcriptText)
            val transcriptPath = transcriptFile.absolutePath

            // 3. Perform Emotional Analysis
            val emotionalTone = analyzeEmotionalTone(transcriptText)

            // 4. Update Repository
            recordingRepository.updateDraft(draft.copy(
                localTranscriptPath = transcriptPath,
                emotionalTone = emotionalTone,
                updatedAt = System.currentTimeMillis()
            ))

            Log.d("TranscriptionWorker", "Transcription completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e("TranscriptionWorker", "Error during transcription: ${e.message}", e)
            recordingRepository.updateDraft(draft.copy(draftState = ArtifactDraftState.ERROR))
            Result.retry()
        } finally {
            tempDecryptedFile?.delete()
        }
    }

    private suspend fun performTranscription(file: File): String {
        delay(3000) // Simulate work
        return "This is a placeholder transcript for the emotionally rich voice recording."
    }

    private suspend fun analyzeEmotionalTone(text: String): String {
        return "Warm, Intimate, Calm"
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
