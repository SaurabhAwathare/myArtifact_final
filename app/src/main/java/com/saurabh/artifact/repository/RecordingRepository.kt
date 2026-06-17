package com.saurabh.artifact.repository

import android.util.Log
import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.audio.DraftDeletionManager
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.audio.WavHeaderUtils
import com.saurabh.artifact.audio.WavRecoveryManager
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
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
    private val cleanupManager: ArtifactCleanupManager,
) {
    
    suspend fun createDraft(
        id: String,
        path: String,
        durationMs: Long,
        checksum: String? = null,
        isEncrypted: Boolean = false,
        mimeType: String = "audio/wav"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val draft = ArtifactDraftEntity(
                id = id,
                localAudioPath = path,
                rawPcmPath = path, // Track durable source
                durationMs = durationMs,
                checksum = checksum,
                isEncrypted = isEncrypted,
                mimeType = mimeType,
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

    fun observeDraft(id: String): Flow<ArtifactDraftEntity?> = draftDao.observeDraftById(id).onEach { draft ->
        if (draft != null) {
            android.util.Log.d("DB_TRACE", "[DB_TRACE] observeDraft emission: draftId=${draft.id}, lifecycle=${draft.lifecycle}, reviewProgress=${draft.reviewProgress}")
        } else {
            android.util.Log.d("DB_TRACE", "[DB_TRACE] observeDraft emission: NULL for $id")
        }
    }

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

    suspend fun updateDraftMetadata(id: String, title: String?, emotion: Emotion?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftDao.updateMetadata(id, title, emotion)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateLifecycle(id: String, lifecycle: ArtifactLifecycle, isRecovery: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        android.util.Log.d("STATE_TRACE", "updateLifecycle: ID=$id, NEW=$lifecycle, isRecovery=$isRecovery (DB_TRACE)")
        try {
            draftDao.updateLifecycle(id, lifecycle, isRecovery = isRecovery)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun recoverDraft(id: String, lifecycle: ArtifactLifecycle): Result<Unit> = withContext(Dispatchers.IO) {
        android.util.Log.d("STATE_TRACE", "recoverDraft: ID=$id, NEW=$lifecycle (RECOVERY)")
        try {
            draftDao.updateLifecycle(id, lifecycle, isRecovery = true)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateTranscriptionResult(id: String, localTranscriptPath: String, emotionalTone: EmotionalTone?, primaryStyle: ConversationStyle?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftDao.updateTranscriptionResult(id, localTranscriptPath, emotionalTone, primaryStyle)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateProcessingStatus(id: String, status: ProcessingStatus): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftDao.updateProcessingStatus(id, status)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateWaveform(id: String, amplitudeData: List<Float>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftDao.updateWaveformResult(id, amplitudeData)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateSafetyResult(id: String, safetyAnalysis: String?, emotionalRiskScore: Float): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftDao.updateSafetyResult(id, safetyAnalysis, emotionalRiskScore)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun finalizeProcessing(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftDao.finalizeProcessing(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateStudioState(
        id: String,
        review: Boolean,
        title: Boolean,
        emotion: Boolean,
        approval: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        android.util.Log.d("STATE_TRACE", "updateStudioState: ID=$id, R=$review, T=$title, E=$emotion, A=$approval (DB_TRACE)")
        try {
            draftDao.updateStudioState(id, review, title, emotion, approval)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun recoverInterruptedDrafts(): Result<List<ArtifactDraftEntity>> = withContext(Dispatchers.IO) {
        try {
            Log.d("RecordingRepository", "Starting recovery check...")
            
            // 0. Repair Lifecycle Desynchronization
            reconcileLifecycleConsistency()

            // 0.1 Purge Zombies: Delete 0-byte drafts older than 30 mins
            purgeZombieDrafts()

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

                    // Calculate recovered duration
                    val recoveredAudioBytes = file.length() - WavHeaderUtils.HEADER_SIZE
                    val recoveredDurationMs = WavHeaderUtils.calculateDurationMs(
                        audioDataLength = recoveredAudioBytes.coerceAtLeast(0),
                        sampleRate = 44100, // Matching WavRecoveryManager defaults
                        channels = 1,
                        bitsPerSample = 16
                    )

                    val updated = draft.copy(
                        status = draft.status.copy(lifecycle = newLifecycle, processing = newProcessing),
                        lifecycle = newLifecycle,
                        durationMs = if (newLifecycle == ArtifactLifecycle.PROCESSING) recoveredDurationMs else draft.durationMs,
                        durableBytes = if (newLifecycle == ArtifactLifecycle.PROCESSING) recoveredAudioBytes.coerceAtLeast(0) else draft.durableBytes,
                        updatedAt = System.currentTimeMillis()
                    )
                    draftDao.update(updated, isRecovery = true)
                    interrupted.add(updated)
                    
                    Log.d("RecordingRepository", "Recovery for ${draft.id}: $recoveryResult -> New Lifecycle: $newLifecycle")
                }
            }

            // 2. Storage Reconciliation
            try {
                val allDrafts = draftDao.getAllDrafts()
                localDraftManager.reconcileStorage(allDrafts)
                
                // Trigger emergency cleanup if storage is critically low
                cleanupManager.triggerEmergencyCleanup()

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

    /**
     * Identifies and purges "zombie" drafts: abandoned recordings with no duration/data.
     */
    private suspend fun purgeZombieDrafts() {
        val now = System.currentTimeMillis()
        val zombieThreshold = 30 * 60 * 1000 // 30 minutes
        
        val activeDrafts = draftDao.getAllDrafts().filter { 
            it.lifecycle == ArtifactLifecycle.RECORDING || it.lifecycle == ArtifactLifecycle.PROCESSING 
        }
        
        activeDrafts.forEach { draft ->
            val isZombieCandidate = draft.durationMs == 0L || draft.durableBytes == 0L
            val isOldEnough = (now - draft.updatedAt) > zombieThreshold
            
            if (isZombieCandidate && isOldEnough) {
                Log.i("RecordingRepository", "Purging zombie draft: ${draft.id} (Lifecycle: ${draft.lifecycle})")
                deletionManager.deleteDraft(draft.id)
            }
        }
    }

    /**
     * Authoritative repair for any desynchronized lifecycle fields.
     * Ensures that the top-level column and embedded JSON status remain consistent.
     */
    private suspend fun reconcileLifecycleConsistency() {
        try {
            val drafts = draftDao.getAllDrafts()
            drafts.forEach { draft ->
                if (draft.lifecycle != draft.status.lifecycle) {
                    Log.w("RecordingRepository", "REPAIR: Synchronizing lifecycle for ${draft.id}: Column=${draft.lifecycle}, JSON=${draft.status.lifecycle}")
                    val fixedStatus = draft.status.copy(lifecycle = draft.lifecycle)
                    draftDao.updateStatusAndLifecycle(
                        id = draft.id,
                        status = fixedStatus,
                        lifecycle = draft.lifecycle,
                        isRecovery = true // Allow backward sync if it was already desynchronized
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("RecordingRepository", "Failed to reconcile lifecycle consistency", e)
        }
    }
}
