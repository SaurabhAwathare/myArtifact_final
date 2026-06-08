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
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
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
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateRecordingProgress(
        id: String,
        durationMs: Long,
        amplitudes: List<Float>,
        durableBytes: Long = 0
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftDao.updateRecordingCheckpoint(
                id = id,
                durationMs = durationMs,
                amplitudes = amplitudes,
                checkpointTimestamp = System.currentTimeMillis(),
                durableBytes = durableBytes
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    fun observeDrafts(): Flow<List<ArtifactDraftEntity>> = draftDao.observeDrafts()

    fun observeDraft(id: String): Flow<ArtifactDraftEntity?> = draftDao.observeDraftById(id)

    suspend fun getDraft(id: String): Result<ArtifactDraftEntity> = withContext(Dispatchers.IO) {
        try {
            val draft = draftDao.getDraftById(id)
            if (draft != null) {
                Result.success(draft)
            } else {
                Result.failure(AppError.NotFound("Draft", id))
            }
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun getDraftByPath(path: String): Result<ArtifactDraftEntity> = withContext(Dispatchers.IO) {
        try {
            val draft = draftDao.getDraftByPath(path)
            if (draft != null) {
                Result.success(draft)
            } else {
                Result.failure(AppError.NotFound("Draft", path))
            }
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateDraft(draft: ArtifactDraftEntity): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftDao.update(draft.copy(updatedAt = System.currentTimeMillis()))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun renameDraft(id: String, newTitle: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val trimmedTitle = newTitle?.trim()
            if ((trimmedTitle != null) && (trimmedTitle.isEmpty() || trimmedTitle.length > 70)) {
                return@withContext Result.failure(AppError.InvalidInput("Title length must be 1-70 characters"))
            }
            draftDao.updateTitle(id, trimmedTitle)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateDraftMetadata(id: String, title: String?, emotion: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftDao.updateMetadata(id, title, emotion)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun recoverInterruptedDrafts(): Result<List<ArtifactDraftEntity>> = withContext(Dispatchers.IO) {
        try {
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
                            ArtifactLifecycle.DELETED to ProcessingStatus.Failed
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

            Result.success(interrupted)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }
}
