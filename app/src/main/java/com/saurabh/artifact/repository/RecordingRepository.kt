package com.saurabh.artifact.repository

import android.content.Context
import androidx.work.*
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.model.SyncState
import com.saurabh.artifact.model.UploadStatus
import com.saurabh.artifact.worker.AudioNormalizationWorker
import com.saurabh.artifact.worker.PrivacyScanWorker
import com.saurabh.artifact.worker.SafetyAnalysisWorker
import com.saurabh.artifact.worker.TranscriptionWorker
import com.saurabh.artifact.worker.WaveformWorker
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
    private val draftDao: DraftDao,
    private val localDraftManager: LocalDraftManager,
    private val uploadGuard: com.saurabh.artifact.security.UploadGuard,
    private val authRepository: AuthRepository
) {
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    
    suspend fun startDraft(file: File) = createDraft(file.absolutePath, 0)

    suspend fun createDraft(
        path: String,
        durationMs: Long,
        checksum: String? = null,
        isEncrypted: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val draftId = UUID.randomUUID().toString()
        val draft = ArtifactDraftEntity(
            id = draftId,
            localAudioPath = path,
            durationMs = durationMs,
            checksum = checksum,
            isEncrypted = isEncrypted,
            syncState = if (durationMs > 0) SyncState.STAGED else SyncState.RECORDING,
            draftState = ArtifactDraftState.RECORDING,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        draftDao.insert(draft)
        draftId
    }

    suspend fun updateRecordingProgress(
        id: String,
        durationMs: Long,
        amplitudes: List<Float>
    ) = withContext(Dispatchers.IO) {
        draftDao.updateRecordingCheckpoint(
            id = id,
            durationMs = durationMs,
            amplitudes = amplitudes,
            checkpointTs = System.currentTimeMillis()
        )
    }

    suspend fun finalizeRecording(
        id: String,
        audioPath: String,
        checksum: String? = null,
        isEncrypted: Boolean = false,
        title: String? = null
    ) = withContext(Dispatchers.IO) {
        val draft = draftDao.getDraftById(id) ?: return@withContext
        draftDao.update(draft.copy(
            localAudioPath = audioPath,
            syncState = SyncState.STAGED,
            checksum = checksum,
            isEncrypted = isEncrypted,
            durationMs = draft.durationMs,
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
        draftDao.updateReviewProgress(id, positionMs, draft?.reviewCoverageBitmask ?: "")
    }

    suspend fun updateLastPlaybackPosition(id: String, positionMs: Long) = withContext(Dispatchers.IO) {
        draftDao.updateLastPlaybackPosition(id, positionMs)
    }

    suspend fun renameDraft(id: String, newTitle: String) = withContext(Dispatchers.IO) {
        draftDao.updateTitle(id, newTitle)
    }

    suspend fun deleteDraft(draft: ArtifactDraftEntity) {
        draftDao.delete(draft)
        localDraftManager.deleteDraft(draft.localAudioPath)
        draft.waveformPath?.let { localDraftManager.deleteDraft(it) }
        draft.localTranscriptPath?.let { localDraftManager.deleteDraft(it) }
    }

    suspend fun approveForUpload(draftId: String) {
        val draft = draftDao.getDraftById(draftId) ?: return
        val userId = authRepository.currentUser.value?.uid ?: "anonymous"
        val timestamp = System.currentTimeMillis()
        
        val token = uploadGuard.generateApprovalToken(userId, draftId, timestamp)
        val fingerprint = uploadGuard.getDeviceFingerprint()

        draftDao.update(draft.copy(
            draftState = ArtifactDraftState.READY_TO_PUBLISH,
            approvalToken = token,
            publishApprovalTimestamp = timestamp,
            deviceFingerprint = fingerprint,
            uploadStatus = UploadStatus.QUEUED,
            syncState = SyncState.QUEUED,
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun revokeApproval(draftId: String) {
        val draft = draftDao.getDraftById(draftId) ?: return
        
        workManager.cancelUniqueWork("sync_$draftId")

        draftDao.update(draft.copy(
            draftState = ArtifactDraftState.SAVED_LOCALLY,
            revocationTimestamp = System.currentTimeMillis(),
            uploadStatus = UploadStatus.LOCAL_ONLY,
            syncState = SyncState.STAGED,
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun recoverInterruptedDrafts(): List<ArtifactDraftEntity> {
        val recordings = draftDao.getActiveRecordings()
        val interrupted = mutableListOf<ArtifactDraftEntity>()
        
        recordings.forEach { draft ->
            if (System.currentTimeMillis() - draft.lastCheckpointTs > 60_000) {
                val updated = draft.copy(
                    syncState = SyncState.INTERRUPTED,
                    updatedAt = System.currentTimeMillis()
                )
                draftDao.update(updated)
                interrupted.add(updated)
            }
        }
        return interrupted
    }

    suspend fun startProcessing(draftId: String) = withContext(Dispatchers.IO) {
        // Optimistic state update
        draftDao.getDraftById(draftId)?.let {
            draftDao.update(it.copy(draftState = ArtifactDraftState.PROCESSING))
        }

        val inputData = workDataOf(AudioNormalizationWorker.KEY_DRAFT_ID to draftId)

        val normalizationWork = OneTimeWorkRequestBuilder<AudioNormalizationWorker>()
            .setInputData(inputData)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        val waveformWork = OneTimeWorkRequestBuilder<WaveformWorker>()
            .setInputData(inputData)
            .build()

        val transcriptionWork = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(inputData)
            .build()

        workManager.beginUniqueWork(
            "process_$draftId",
            ExistingWorkPolicy.REPLACE,
            normalizationWork
        )
        .then(waveformWork)
        .then(transcriptionWork)
        .enqueue()
    }
}
