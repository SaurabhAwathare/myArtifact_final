package com.saurabh.artifact.repository

import android.util.Log
import com.saurabh.artifact.audio.DraftDeletionManager
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.audio.WavRecoveryManager
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingRepository @Inject constructor(
    private val draftDao: DraftDao,
    private val localDraftManager: LocalDraftManager,
    private val wavRecoveryManager: WavRecoveryManager,
    private val deletionManager: DraftDeletionManager,
) {
    
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
                publication = SyncStatus.LocalOnly
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
            checkpointTimestamp = System.currentTimeMillis(),
            durableBytes = durableBytes
        )
    }

    fun observeDrafts(): Flow<List<ArtifactDraftEntity>> = draftDao.observeDrafts()

    fun observeDraft(id: String): Flow<ArtifactDraftEntity?> = draftDao.observeDraftById(id)

    suspend fun getDraft(id: String): ArtifactDraftEntity? = draftDao.getDraftById(id)

    suspend fun getDraftByPath(path: String): ArtifactDraftEntity? = draftDao.getDraftByPath(path)

    suspend fun updateDraft(draft: ArtifactDraftEntity) {
        draftDao.update(draft.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun renameDraft(id: String, newTitle: String?) = withContext(Dispatchers.IO) {
        val trimmedTitle = newTitle?.trim()
        if ((trimmedTitle != null) && (trimmedTitle.isEmpty() || trimmedTitle.length > 70)) {
            return@withContext
        }
        draftDao.updateTitle(id, trimmedTitle)
    }

    suspend fun updateDraftMetadata(id: String, title: String?, emotion: String?) = withContext(Dispatchers.IO) {
        draftDao.updateMetadata(id, title, emotion)
    }

    suspend fun recoverInterruptedDrafts(): List<ArtifactDraftEntity> {
        Log.d("RecordingRepository", "Starting recovery check...")
        
        // 1. Recover interrupted recordings
        val recordings = draftDao.getActiveRecordings()
        val interrupted = mutableListOf<ArtifactDraftEntity>()
        
        recordings.forEach { draft ->
            // If no checkpoint for > 60s, consider it interrupted
            if ((System.currentTimeMillis() - draft.lastCheckpointTimestamp) > 60_000) {
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
                    WavRecoveryManager.RecoveryResult.REPAIRED,
                    WavRecoveryManager.RecoveryResult.FULLY_RECOVERED,
                    WavRecoveryManager.RecoveryResult.TRUNCATED -> 
                        ArtifactLifecycle.PROCESSING to ProcessingStatus.Idle
                    WavRecoveryManager.RecoveryResult.CORRUPTED,
                    WavRecoveryManager.RecoveryResult.NOT_FOUND ->
                        ArtifactLifecycle.DELETED to ProcessingStatus.Failed()
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

        // 2. Storage Reconciliation
        try {
            val allDrafts = draftDao.getAllDrafts()
            localDraftManager.reconcileStorage(allDrafts)
            
            // 3. Authoritative cleanup for DELETING drafts
            val deletingDrafts = draftDao.getDraftsByLifecycle(ArtifactLifecycle.DELETING)
            deletingDrafts.forEach { draft ->
                Log.d("RecordingRepository", "Resuming deletion for draft: ${draft.id}")
                deletionManager.deleteDraft(draft.id)
            }
        } catch (_: Exception) {
            Log.e("RecordingRepository", "Cleanup orphans/deleting failed")
        }

        return interrupted
    }
}
