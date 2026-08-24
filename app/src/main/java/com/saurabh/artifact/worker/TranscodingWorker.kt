package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.util.EncryptedStorageManager
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.*
import com.saurabh.artifact.audio.WavRecoveryManager
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.util.FileIntegrity
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/**
 * TranscodingWorker: The bridge between "Durable Capture" and "Optimized Persistence".
 * Converts raw WAV/PCM data to high-quality AAC/M4A.
 */
@OptIn(UnstableApi::class)
@HiltWorker
class TranscodingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val draftDao: Lazy<DraftDao>,
    private val localDraftManager: LocalDraftManager,
    private val encryptedStorageManager: EncryptedStorageManager,
    private val wavRecoveryManager: WavRecoveryManager,
    private val authRepository: com.saurabh.artifact.repository.AuthRepository,
    private val startupCoordinator: com.saurabh.artifact.startup.StartupCoordinator,
    private val diagnosticLogger: DiagnosticLogger,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // WORKER LOCK: Ensure database encryption is ready before proceeding
        startupCoordinator.awaitComponent(com.saurabh.artifact.startup.StartupComponent.DATABASE)

        val draftId = inputData.getString(AudioNormalizationWorker.KEY_DRAFT_ID) ?: return@withContext Result.failure()
        val userId = authRepository.currentUserId
        
        if (userId.isEmpty()) return@withContext Result.failure()

        diagnosticLogger.info(DiagnosticCategory.RECORDING, "TRANSCODING_STARTED", mapOf(LogKeys.DRAFT_ID to draftId))
        
        val draft = draftDao.get().getDraftById(draftId, userId) ?: return@withContext Result.failure()

        val rawFile = draft.rawPcmPath?.let { File(it) } ?: return@withContext Result.failure()
        if (!rawFile.exists()) {
            diagnosticLogger.error(DiagnosticCategory.RECORDING, "TRANSCODING_RAW_FILE_MISSING", mapOf(LogKeys.DRAFT_ID to draftId))
            return@withContext Result.failure()
        }

        try {
            // IDEMPOTENCY CHECK: If the artifact already exists and metadata is correct, skip
            val existingFile = File(draft.localAudioPath)
            if (existingFile.exists() && (existingFile.length() > 0) && draft.isEncrypted) {
                diagnosticLogger.info(DiagnosticCategory.RECORDING, "TRANSCODING_SKIP_IDEMPOTENT", mapOf(LogKeys.DRAFT_ID to draftId))
                return@withContext Result.success()
            }

            updateDraftStatus(draftId, userId, ProcessingStage.TRANSCODING)
            
            // 0. Defense-in-Depth: Validate and repair WAV header before transcoding
            val recoveryResult = wavRecoveryManager.recover(rawFile)
            if (recoveryResult == WavRecoveryManager.RecoveryResult.CORRUPTED) {
                diagnosticLogger.error(DiagnosticCategory.RECORDING, "TRANSCODING_WAV_CORRUPTED", mapOf(LogKeys.DRAFT_ID to draftId))
                updateDraftStatus(draftId, userId, null, "Unrecoverable WAV header")
                return@withContext Result.failure()
            }

            // 1. Transcode raw WAV to finalized location (Encrypted)
            val finalAudioFile = localDraftManager.createDraftFile(draftId, "m4a")
            diagnosticLogger.debug(DiagnosticCategory.RECORDING, "TRANSCODING_PROCESSING", mapOf(LogKeys.DRAFT_ID to draftId))
            transcodeAndEncrypt(rawFile, finalAudioFile)
            
            // 2. Metadata Extraction (Checksum)
            val checksum = FileIntegrity.calculateChecksum(finalAudioFile.absolutePath)
            
            // 2.1 ATOMICITY FIX: Verify generated file essence before DB commitment
            if (!finalAudioFile.exists() || finalAudioFile.length() == 0L) {
                diagnosticLogger.error(DiagnosticCategory.RECORDING, "TRANSCODING_OUTPUT_EMPTY", mapOf(LogKeys.DRAFT_ID to draftId))
                updateDraftStatus(draftId, userId, null, "Transcoding output verification failed")
                return@withContext Result.failure()
            }

            // 3. Finalize paths in DB with targeted update (Commit before cleanup)
            draftDao.get().updateTranscodingResult(
                id = draftId,
                userId = userId,
                localAudioPath = finalAudioFile.absolutePath,
                checksum = checksum,
                isEncrypted = true
            )

            diagnosticLogger.info(DiagnosticCategory.RECORDING, "TRANSCODING_SUCCESS", mapOf(LogKeys.DRAFT_ID to draftId))
            Result.success()
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.RECORDING, "TRANSCODING_FAILED", mapOf(LogKeys.DRAFT_ID to draftId), e)
            updateDraftStatus(draftId, userId, null, "Transcoding failed: ${e.message}")
            Result.retry()
        }
    }

    private fun transcodeAndEncrypt(input: File, output: File) {
        // We use the encrypted output stream for the destination
        encryptedStorageManager.getEncryptedOutputStream(output).use { encryptedOut ->
            input.inputStream().use { it.copyTo(encryptedOut) }
        }
    }

    private suspend fun updateDraftStatus(id: String, userId: String, stage: ProcessingStage?, error: String? = null) {
        val newProcessing = when {
            error != null -> ProcessingStatus.Failed
            stage != null -> ProcessingStatus.Active(stage)
            else -> ProcessingStatus.Idle
        }
        draftDao.get().updateProcessingStatus(id, userId, newProcessing)
    }
}
