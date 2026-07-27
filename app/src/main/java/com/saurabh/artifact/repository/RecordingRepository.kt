package com.saurabh.artifact.repository

import androidx.room.withTransaction
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.saurabh.artifact.util.WorkNames
import androidx.lifecycle.asFlow
import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.audio.WavHeaderUtils
import com.saurabh.artifact.audio.WavRecoveryManager
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingRepository @Inject constructor(
    private val draftDao: dagger.Lazy<DraftDao>,
    private val userRepository: UserRepository,
    private val localDraftManager: LocalDraftManager,
    private val wavRecoveryManager: WavRecoveryManager,
    private val cleanupManager: ArtifactCleanupManager,
    private val draftsDatabase: dagger.Lazy<com.saurabh.artifact.data.local.AppDatabase>,
    private val diagnosticLogger: DiagnosticLogger
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
                lifecycle = if (durationMs > 0) ArtifactLifecycle.PROCESSING else ArtifactLifecycle.RECORDING,
                mimeType = mimeType,
                status = DraftStatus(
                    publication = SyncStatus.LocalOnly
                ),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            draftDao.get().insert(draft)
            
            // Increment artifactsCount on the user's Firestore document
            val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            if (currentUserId != null) {
                userRepository.incrementArtifactsCount(currentUserId)
            }

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
            draftDao.get().updateRecordingCheckpoint(
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

    fun observeDrafts(): Flow<List<ArtifactDraftEntity>> = draftDao.get().observeDrafts()

    fun observeDraft(id: String): Flow<ArtifactDraftEntity?> = draftDao.get().observeDraftById(id)
        .distinctUntilChanged()
        .onEach { draft ->
            if (draft != null) {
                diagnosticLogger.trace(
                    DiagnosticCategory.DATABASE,
                    "DRAFT_OBSERVE_EMISSION",
                    mapOf(
                        LogKeys.DRAFT_ID to draft.id,
                        "lifecycle" to draft.lifecycle.name,
                        "reviewProgress" to draft.reviewProgress
                    )
                )
            } else {
                diagnosticLogger.trace(DiagnosticCategory.DATABASE, "DRAFT_OBSERVE_NULL", mapOf(LogKeys.DRAFT_ID to id))
            }
        }

    suspend fun getDraft(id: String): Result<ArtifactDraftEntity> = withContext(Dispatchers.IO) {
        try {
            val draft = draftDao.get().getDraftById(id)
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
            val draft = draftDao.get().getDraftByPath(path)
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
            draftDao.get().update(draft.copy(updatedAt = System.currentTimeMillis()))
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
            draftDao.get().updateTitle(id, trimmedTitle)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateDraftMetadata(id: String, title: String?, emotion: Emotion?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftDao.get().updateMetadata(id, title, emotion)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateLifecycle(id: String, lifecycle: ArtifactLifecycle, isRecovery: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        diagnosticLogger.debug(
            DiagnosticCategory.DATABASE,
            "DRAFT_LIFECYCLE_UPDATE",
            mapOf(LogKeys.DRAFT_ID to id, "lifecycle" to lifecycle.name, "isRecovery" to isRecovery)
        )
        try {
            draftDao.get().updateLifecycle(id, lifecycle, isRecovery = isRecovery)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun recoverDraft(id: String, lifecycle: ArtifactLifecycle): Result<Unit> = withContext(Dispatchers.IO) {
        diagnosticLogger.info(
            DiagnosticCategory.RECORDING,
            "DRAFT_RECOVERY_STARTED",
            mapOf(LogKeys.DRAFT_ID to id, "targetLifecycle" to lifecycle.name)
        )
        try {
            draftDao.get().updateLifecycle(id, lifecycle, isRecovery = true)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateProcessingStatus(id: String, status: ProcessingStatus): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftDao.get().updateProcessingStatus(id, status)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateWaveform(id: String, amplitudeData: List<Float>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftDao.get().updateWaveformResult(id, amplitudeData)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun finalizeProcessing(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftDao.get().finalizeProcessing(id)
            // Mark recovery complete when terminal state reached
            markRecoveryComplete(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Resets the recovery flag. 
     */
    suspend fun markRecoveryComplete(id: String) {
        // No-op for now as state is derived from WorkManager + lastRecoveryAttemptAt
    }

    suspend fun updateStudioState(
        id: String,
        title: Boolean,
        emotion: Boolean,
        approval: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        diagnosticLogger.debug(
            DiagnosticCategory.DATABASE,
            "DRAFT_STUDIO_STATE_UPDATE",
            mapOf(LogKeys.DRAFT_ID to id, "title" to title, "emotion" to emotion, "approval" to approval)
        )
        try {
            draftDao.get().updateStudioState(id, title, emotion, approval)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Atomically finalizes a recording session according to the
     * [Publishing Flow Invariants](file:///docs/architecture/PublishingFlowInvariants.md).
     *
     * This method is idempotent: if the draft is already beyond the RECORDING stage
     * with identical duration/bytes, it performs no action.
     *
     * It ensures durationMs, durableBytes, lifecycle, and updatedAt are updated in one transaction.
     */
    suspend fun finalizeRecording(
        id: String,
        durationMs: Long,
        durableBytes: Long,
        targetLifecycle: ArtifactLifecycle = ArtifactLifecycle.PROCESSING
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftsDatabase.get().withTransaction {
                val existing = draftDao.get().getDraftById(id) ?: throw Exception("Draft not found")

                // Idempotency check: Don't regress or duplicate work if data already matches
                val isSameState = existing.lifecycle == targetLifecycle && 
                                 existing.durationMs == durationMs && 
                                 existing.durableBytes == durableBytes
                
                if (isSameState) return@withTransaction

                if (!existing.lifecycle.canTransitionTo(targetLifecycle)) {
                    // Block the transition if it's a regression
                    throw Exception("Cannot finalize recording: Invalid transition from ${existing.lifecycle} to $targetLifecycle")
                }

                // Update all finalization fields together
                val updated = existing.copy(
                    durationMs = durationMs,
                    durableBytes = durableBytes,
                    lifecycle = targetLifecycle,
                    updatedAt = System.currentTimeMillis()
                )
                draftDao.get().update(updated)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun recoverInterruptedDrafts(): Result<List<ArtifactDraftEntity>> = withContext(Dispatchers.IO) {
        try {
            diagnosticLogger.info(DiagnosticCategory.RECORDING, "INTERRUPTED_DRAFTS_RECOVERY_STARTED")
            
            // 0. Repair Lifecycle Desynchronization
            reconcileLifecycleConsistency()

            // 0.1 Purge Zombies: Delete 0-byte drafts older than 30 mins
            purgeZombieDrafts()

            val now = System.currentTimeMillis()

            // 1. Recover interrupted recordings (RECORDING lifecycle)
            val recordings = draftDao.get().getActiveRecordings()
            val interrupted = mutableListOf<ArtifactDraftEntity>()
            
            recordings.forEach { draft ->
                // If no checkpoint for > 60s, consider it interrupted
                if ((now - draft.lastCheckpointTimestamp) > 60_000) {
                    val file = File(draft.localAudioPath)
                    
                    // Durability Drift Logging
                    if (file.exists()) {
                        val drift = file.length() - draft.durableBytes
                        if (drift < 0) {
                            diagnosticLogger.error(
                                DiagnosticCategory.RECORDING,
                                "RECOVERY_SILENT_TRUNCATION",
                                mapOf(LogKeys.DRAFT_ID to draft.id, "expectedBytes" to draft.durableBytes, "actualBytes" to file.length())
                            )
                        } else {
                            diagnosticLogger.debug(
                                DiagnosticCategory.RECORDING,
                                "RECOVERY_DRIFT_DETECTED",
                                mapOf(LogKeys.DRAFT_ID to draft.id, "driftBytes" to drift)
                            )
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
                        status = draft.status.copy(processing = newProcessing),
                        lifecycle = newLifecycle,
                        durationMs = if (newLifecycle == ArtifactLifecycle.PROCESSING) recoveredDurationMs else draft.durationMs,
                        durableBytes = if (newLifecycle == ArtifactLifecycle.PROCESSING) recoveredAudioBytes.coerceAtLeast(0) else draft.durableBytes,
                        updatedAt = System.currentTimeMillis()
                    )
                    draftDao.get().update(updated, isRecovery = true)
                    interrupted.add(updated)
                    
                    diagnosticLogger.info(
                        DiagnosticCategory.RECORDING,
                        "RECOVERY_DRAFT_REPAIRED",
                        mapOf(LogKeys.DRAFT_ID to draft.id, "result" to recoveryResult.name, "newLifecycle" to newLifecycle.name)
                    )
                }
            }

            // 2. Recover stalled processing (PROCESSING lifecycle)
            val processingDrafts = draftDao.get().getDraftsByLifecycle(ArtifactLifecycle.PROCESSING)
            processingDrafts.forEach { draft ->
                val isStale = (now - draft.updatedAt) > STALE_PROCESSING_TIMEOUT_MS
                val isCooldownOver = (now - draft.lastRecoveryAttemptAt) > RECOVERY_COOLDOWN_MS
                
                if (isStale && isCooldownOver) {
                    diagnosticLogger.info(DiagnosticCategory.RECORDING, "RECOVERY_STALLED_PROCESSING", mapOf(LogKeys.DRAFT_ID to draft.id))
                    interrupted.add(draft)
                }
            }

            // 3. Storage Reconciliation
            try {
                val allDrafts = draftDao.get().getAllDrafts()
                localDraftManager.reconcileStorage(allDrafts)
                
                // Trigger emergency cleanup if storage is critically low
                cleanupManager.triggerEmergencyCleanup()

                // 4. Authoritative cleanup for DELETING drafts
                val deletingDrafts = draftDao.get().getDraftsByLifecycle(ArtifactLifecycle.DELETING)
                deletingDrafts.forEach { draft ->
                    diagnosticLogger.debug(DiagnosticCategory.RECORDING, "RECOVERY_RESUMING_DELETION", mapOf(LogKeys.DRAFT_ID to draft.id))
                    cleanupManager.deleteDraft(draft.id)
                }
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.RECORDING, "RECOVERY_CLEANUP_FAILED", throwable = e)
            }

            Result.success(interrupted)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.RECORDING, "RECOVERY_FATAL_ERROR", throwable = e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Updates the recovery attempt timestamp to prevent rapid retry loops.
     */
    suspend fun markRecoveryAttempt(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val draft = draftDao.get().getDraftById(id) ?: return@withContext Result.failure(AppError.NotFound("Draft", id))
            draftDao.get().update(draft.copy(lastRecoveryAttemptAt = System.currentTimeMillis()))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Observes the recovery state of a specific draft.
     */
    fun observeRecoveryState(draftId: String, workManager: WorkManager): Flow<Boolean> {
        return observeDraft(draftId).map { draft ->
            if (draft == null) return@map false
            val hasRecoveryMetadata = draft.lifecycle == ArtifactLifecycle.PROCESSING && 
                                    draft.lastRecoveryAttemptAt > draft.updatedAt
            hasRecoveryMetadata
        }.flatMapLatest { isMarkedRecovering ->
            if (!isMarkedRecovering) return@flatMapLatest flowOf(false)
            workManager.getWorkInfosForUniqueWorkLiveData(WorkNames.forProcessing(draftId)).asFlow().map { workInfos ->
                workInfos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            }
        }
    }

    /**
     * Identifies and purges "zombie" drafts: abandoned recordings with no duration/data.
     */
    private suspend fun purgeZombieDrafts() {
        val now = System.currentTimeMillis()
        val zombieThreshold = 30 * 60 * 1000 // 30 minutes
        
        val activeDrafts = draftDao.get().getAllDrafts().filter {
            it.lifecycle == ArtifactLifecycle.RECORDING || it.lifecycle == ArtifactLifecycle.PROCESSING 
        }
        
        activeDrafts.forEach { draft ->
            val isZombieCandidate = draft.durationMs == 0L || draft.durableBytes == 0L
            val isOldEnough = (now - draft.updatedAt) > zombieThreshold
            
            if (isZombieCandidate && isOldEnough) {
                diagnosticLogger.info(DiagnosticCategory.RECORDING, "PURGING_ZOMBIE_DRAFT", mapOf(LogKeys.DRAFT_ID to draft.id, "lifecycle" to draft.lifecycle.name))
                cleanupManager.deleteDraft(draft.id)
            }
        }
    }

    /**
     * Authoritative repair for any desynchronized lifecycle fields.
     */
    private suspend fun reconcileLifecycleConsistency() {
        // No longer needed as status.lifecycle is removed.
    }

    companion object {
        private const val STALE_PROCESSING_TIMEOUT_MS = 15 * 60 * 1000L
        private const val RECOVERY_COOLDOWN_MS = 5 * 60 * 1000L
    }
}
