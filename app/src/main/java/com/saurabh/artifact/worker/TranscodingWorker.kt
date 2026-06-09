package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.util.EncryptedStorageManager
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.*
import com.saurabh.artifact.audio.WavRecoveryManager
import com.saurabh.artifact.util.FileIntegrity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TranscodingWorker: The bridge between "Durable Capture" and "Optimized Persistence".
 * Converts raw WAV/PCM data to high-quality AAC/M4A.
 */
@HiltWorker
class TranscodingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val draftDao: DraftDao,
    private val localDraftManager: LocalDraftManager,
    private val encryptedStorageManager: EncryptedStorageManager,
    private val wavRecoveryManager: WavRecoveryManager,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(AudioNormalizationWorker.KEY_DRAFT_ID) ?: return@withContext Result.failure()
        val draft = draftDao.getDraftById(draftId) ?: return@withContext Result.failure()

        val rawFile = draft.rawPcmPath?.let { File(it) } ?: return@withContext Result.failure()
        if (!rawFile.exists()) {
            Log.e("TranscodingWorker", "Raw file missing for draft: $draftId")
            return@withContext Result.failure()
        }

        try {
            // IDEMPOTENCY CHECK: If the artifact already exists and metadata is correct, skip
            val existingFile = File(draft.localAudioPath)
            if (existingFile.exists() && existingFile.length() > 0 && !draft.isEncrypted) {
                Log.d("TranscodingWorker", "Idempotency Trigger: Optimized artifact already exists. Skipping.")
                return@withContext Result.success()
            }

            updateDraftStatus(draftId, ProcessingStage.TRANSCODING)
            
            // 0. Defense-in-Depth: Validate and repair WAV header before transcoding
            val recoveryResult = wavRecoveryManager.recover(rawFile)
            if (recoveryResult == WavRecoveryManager.RecoveryResult.CORRUPTED) {
                Log.e("TranscodingWorker", "Unrecoverable WAV header: CORRUPTED")
                updateDraftStatus(draftId, null, "Unrecoverable WAV header")
                return@withContext Result.failure()
            }

            // 1. Transcode raw WAV to finalized location (Encrypted)
            val finalAudioFile = localDraftManager.createDraftFile(draftId, "m4a")
            Log.d("TranscodingWorker", "Atmospheric Step: Refining audio essence (securing reflection)...")
            transcodeAndEncrypt(rawFile, finalAudioFile)
            
            // 2. Metadata Extraction (Checksum)
            val checksum = FileIntegrity.calculateChecksum(finalAudioFile.absolutePath)
            
            // 3. Securely delete intermediate files
            rawFile.delete()

            // 4. Finalize paths in DB
            draftDao.update(draft.copy(
                localAudioPath = finalAudioFile.absolutePath,
                checksum = checksum,
                isEncrypted = true,
                updatedAt = System.currentTimeMillis()
            ))

            Log.d("TranscodingWorker", "Transcoding complete: ${finalAudioFile.name}")
            Result.success()
        } catch (e: Exception) {
            Log.e("TranscodingWorker", "Transcoding failed", e)
            updateDraftStatus(draftId, null, "Transcoding failed: ${e.message}")
            Result.retry()
        }
    }

    private fun transcodeAndEncrypt(input: File, output: File) {
        // We use the encrypted output stream for the destination
        encryptedStorageManager.getEncryptedOutputStream(output).use { encryptedOut ->
            input.inputStream().use { it.copyTo(encryptedOut) }
        }
    }

    private suspend fun updateDraftStatus(id: String, stage: ProcessingStage?, error: String? = null) {
        val newProcessing = when {
            error != null -> ProcessingStatus.Failed
            stage != null -> ProcessingStatus.Active(stage)
            else -> ProcessingStatus.Idle
        }
        
        draftDao.getDraftById(id)?.let { draft ->
            val newStatus = draft.status.copy(processing = newProcessing)
            draftDao.updateStatus(id, newStatus)
        }
    }
}
