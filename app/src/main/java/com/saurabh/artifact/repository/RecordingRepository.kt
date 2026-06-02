package com.saurabh.artifact.repository

import android.util.Log
import androidx.work.*
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.*
import com.saurabh.artifact.worker.AudioNormalizationWorker
import com.saurabh.artifact.worker.PrivacyScanWorker
import com.saurabh.artifact.worker.SafetyAnalysisWorker
import com.saurabh.artifact.worker.TranscriptionWorker
import com.saurabh.artifact.worker.WaveformWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingRepository @Inject constructor(
    private val draftDao: DraftDao,
    private val localDraftManager: LocalDraftManager,
    private val wavRecoveryManager: com.saurabh.artifact.audio.WavRecoveryManager,
    private val deletionManager: com.saurabh.artifact.audio.DraftDeletionManager,
    private val workManager: WorkManager
) {
    
    suspend fun startDraft(draftId: String = UUID.randomUUID().toString()) = withContext(Dispatchers.IO) {
        val file = localDraftManager.createDraftFile(draftId)
        createDraft(draftId, file.absolutePath, 0)
    }

    suspend fun createDraft(
        id: String,
        path: String,
        durationMs: Long,
        checksum: String? = null,
        isEncrypted: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val draft = ArtifactDraftEntity(
            id = id,
            localAudioPath = path,
            rawPcmPath = path, // Track durable source
            durationMs = durationMs,
            checksum = checksum,
            isEncrypted = isEncrypted,
            status = DraftStatus(
                lifecycle = if (durationMs > 0) ArtifactLifecycle.PROCESSING else ArtifactLifecycle.RECORDING,
                sync = SyncStatus.LocalOnly
            ),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        draftDao.insert(draft)
        id
    }

    suspend fun updateRecordingProgress(
        id: String,
        durationMs: Long,
        amplitudes: List<Float>,
        durableBytes: Long = 0
    ) = withContext(Dispatchers.IO) {
        draftDao.updateRecordingCheckpoint(
            id = id,
            durationMs = durationMs,
            amplitudes = amplitudes,
            checkpointTs = System.currentTimeMillis(),
            durableBytes = durableBytes
        )
    }

    suspend fun finalizeRecording(
        id: String,
        audioPath: String,
        checksum: String? = null,
        title: String? = null
    ) = withContext(Dispatchers.IO) {
        val draft = draftDao.getDraftById(id) ?: return@withContext
        val finalFile = File(audioPath)
        val finalSize = if (finalFile.exists()) finalFile.length() else 0L
        
        draftDao.update(draft.copy(
            localAudioPath = audioPath,
            status = draft.status.copy(lifecycle = ArtifactLifecycle.PROCESSING),
            checksum = checksum,
            isEncrypted = false,
            durationMs = draft.durationMs,
            durableBytes = finalSize, // Finalize durable bytes to full file size
            title = title ?: draft.title,
            updatedAt = System.currentTimeMillis()
        ))
    }

    fun observeDrafts(): Flow<List<ArtifactDraftEntity>> = draftDao.observeDrafts()

    fun observeDraft(id: String): Flow<ArtifactDraftEntity?> = draftDao.observeDraftById(id)

    suspend fun getDraft(id: String): ArtifactDraftEntity? = draftDao.getDraftById(id)

    suspend fun getDraftByPath(path: String): ArtifactDraftEntity? = draftDao.getDraftByPath(path)

    suspend fun updateDraft(draft: ArtifactDraftEntity) {
        draftDao.update(draft.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateReviewProgress(id: String, positionMs: Long) = withContext(Dispatchers.IO) {
        val draft = draftDao.getDraftById(id)
        draftDao.updateReviewProgress(id, positionMs, draft?.reviewCoverageBitmask ?: 0L)
    }

    suspend fun updateLastPlaybackPosition(id: String, positionMs: Long) = withContext(Dispatchers.IO) {
        draftDao.updateLastPlaybackPosition(id, positionMs)
    }

    suspend fun renameDraft(id: String, newTitle: String?) = withContext(Dispatchers.IO) {
        draftDao.updateTitle(id, newTitle)
    }

    suspend fun updateDraftMetadata(id: String, title: String?, emotion: String?) = withContext(Dispatchers.IO) {
        draftDao.updateMetadata(id, title, emotion)
    }

    suspend fun deleteDraft(draft: ArtifactDraftEntity) {
        deletionManager.deleteDraft(draft.id)
    }

    suspend fun deleteDraftById(id: String) = withContext(Dispatchers.IO) {
        deletionManager.deleteDraft(id)
    }

    suspend fun recoverInterruptedDrafts(): List<ArtifactDraftEntity> {
        val recordings = draftDao.getActiveRecordings()
        val interrupted = mutableListOf<ArtifactDraftEntity>()
        
        recordings.forEach { draft ->
            // If no checkpoint for > 60s, consider it interrupted
            if ((System.currentTimeMillis() - draft.lastCheckpointTs) > 60_000) {
                val file = File(draft.localAudioPath)
                
                // Durability Drift Logging
                if (file.exists()) {
                    val drift = file.length() - draft.durableBytes
                    if (drift < 0) {
                        Log.e("RecordingRepository", "CRITICAL: Silent truncation detected for draft ${draft.id}. Metadata expects ${draft.durableBytes} bytes, but file is ${file.length()} bytes.")
                    } else {
                        Log.d("RecordingRepository", "Recovery drift for ${draft.id}: $drift bytes (uncommitted Page Cache tail)")
                    }
                }

                val recoveryResult = wavRecoveryManager.recover(file, lastDurableBytes = draft.durableBytes)
                
                val (newLifecycle, newProcessing) = when (recoveryResult) {
                    com.saurabh.artifact.audio.WavRecoveryManager.RecoveryResult.REPAIRED,
                    com.saurabh.artifact.audio.WavRecoveryManager.RecoveryResult.FULLY_RECOVERED,
                    com.saurabh.artifact.audio.WavRecoveryManager.RecoveryResult.TRUNCATED -> 
                        ArtifactLifecycle.PROCESSING to ProcessingStatus.Idle
                    com.saurabh.artifact.audio.WavRecoveryManager.RecoveryResult.CORRUPTED,
                    com.saurabh.artifact.audio.WavRecoveryManager.RecoveryResult.NOT_FOUND -> 
                        ArtifactLifecycle.DELETED to ProcessingStatus.Failed("Corruption detected")
                }

                val updated = draft.copy(
                    status = draft.status.copy(lifecycle = newLifecycle, processing = newProcessing),
                    updatedAt = System.currentTimeMillis()
                )
                draftDao.update(updated)
                interrupted.add(updated)
                
                Log.d("RecordingRepository", "Recovery for ${draft.id}: $recoveryResult -> New Lifecycle: $newLifecycle")
            }
        }
        return interrupted
    }

    suspend fun startProcessing(draftId: String) = withContext(Dispatchers.IO) {
        // Optimistic state update
        draftDao.getDraftById(draftId)?.let {
            draftDao.update(it.copy(
                status = it.status.copy(
                    lifecycle = ArtifactLifecycle.PROCESSING,
                    processing = ProcessingStatus.Active(ProcessingStage.TRANSCODING)
                )
            ))
        }

        val inputData = workDataOf(AudioNormalizationWorker.KEY_DRAFT_ID to draftId)

        val transcodingWork = OneTimeWorkRequestBuilder<com.saurabh.artifact.worker.TranscodingWorker>()
            .setInputData(inputData)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        val normalizationWork = OneTimeWorkRequestBuilder<AudioNormalizationWorker>()
            .setInputData(inputData)
            .build()

        val waveformWork = OneTimeWorkRequestBuilder<WaveformWorker>()
            .setInputData(inputData)
            .build()

        val transcriptionWork = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(inputData)
            .build()

        val privacyWork = OneTimeWorkRequestBuilder<PrivacyScanWorker>()
            .setInputData(inputData)
            .build()

        val safetyWork = OneTimeWorkRequestBuilder<SafetyAnalysisWorker>()
            .setInputData(inputData)
            .build()

        val finalStateWork = OneTimeWorkRequestBuilder<com.saurabh.artifact.worker.ProcessingFinalizerWorker>()
            .setInputData(inputData)
            .build()

        workManager.beginUniqueWork(
            "process_$draftId",
            ExistingWorkPolicy.REPLACE,
            transcodingWork
        )
        .then(normalizationWork)
        .then(waveformWork)
        .then(transcriptionWork)
        .then(privacyWork)
        .then(safetyWork)
        .then(finalStateWork)
        .enqueue()
    }
}
