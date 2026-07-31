package com.saurabh.artifact.repository

import androidx.room.withTransaction
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.data.mapper.DraftToArtifactMapper
import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DraftRepository @Inject constructor(
    private val draftDao: DraftDao,
    private val uploadTaskDao: UploadTaskDao,
    private val draftsDatabase: AppDatabase,
    private val draftToArtifactMapper: DraftToArtifactMapper,
    private val userRepository: UserRepository
) {
    suspend fun getDraft(id: String): Result<ArtifactDraftEntity> = withContext(Dispatchers.IO) {
        try {
            val userId = userRepository.getCurrentUserId() ?: return@withContext Result.failure(AppError.Unauthenticated())
            val draft = draftDao.getDraftById(id, userId)
            if (draft != null) {
                Result.success(draft)
            } else {
                Result.failure(AppError.NotFound("Draft", id))
            }
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    fun observeDrafts(): Flow<List<ArtifactDraftEntity>> {
        val userId = userRepository.getCurrentUserId() ?: return flowOf(emptyList())
        return draftDao.observeDrafts(userId)
    }

    /**
     * Observes a single draft and maps it to a domain Artifact.
     * Combines with the user profile to ensure correct author identity.
     */
    fun observeDraftAsArtifact(id: String): Flow<Artifact?> {
        val currentUserId = userRepository.getCurrentUserId() ?: return flowOf(null)

        val optimizedDraftFlow = draftDao.observeDraftById(id, currentUserId)
            .distinctUntilChanged { old, new ->
                PlaybackKey.from(old) == PlaybackKey.from(new)
            }

        return combine(
            optimizedDraftFlow,
            userRepository.streamUserProfile(currentUserId)
        ) { draft, user ->
            if (draft == null || user == null) {
                null
            } else {
                val artifact = draftToArtifactMapper.map(
                    draft = draft,
                    author = AuthorSnapshot.fromUser(user),
                    fallbackTitle = "Untitled"
                )
                
                ArtifactLogger.d(
                    DiagnosticCategory.DATABASE, 
                    "TRANSCRIPT_DRAFT_EMITTED", 
                    mapOf(
                        LogKeys.DRAFT_ID to draft.id,
                        "segmentCount" to artifact.transcript.size
                    )
                )
                
                artifact
            }
        }
    }

    /**
     * Observes local drafts that are actively in progress (not yet published).
     */
    fun observeActiveDrafts(): Flow<List<ArtifactDraftEntity>> {
        return observeDrafts().map { drafts ->
            drafts.filter { 
                it.lifecycle != ArtifactLifecycle.PUBLISHED && 
                it.lifecycle != ArtifactLifecycle.READY_TO_PUBLISH 
            }
        }
    }

    /**
     * Observes drafts that are currently in the publishing pipeline.
     */
    fun observePublishingDrafts(): Flow<List<ArtifactDraftEntity>> {
        return observeDrafts().map { drafts ->
            drafts.filter { it.lifecycle == ArtifactLifecycle.READY_TO_PUBLISH }
                .sortedByDescending { it.updatedAt }
        }
    }

    /**
     * Observes the most relevant publishing/processing session for global UI progress indicators.
     * Centralizes the priority logic to ensure a single source of truth.
     */
    fun observeActivePublishingSessionWithUpload(): Flow<DraftWithUpload?> {
        return observeDraftsWithUploads().map { list ->
            // Priority 1: Actively publishing or finalizing (READY_TO_PUBLISH)
            val publishing = list.filter { it.draft.lifecycle == ArtifactLifecycle.READY_TO_PUBLISH }
                .maxByOrNull { it.draft.updatedAt }
            if (publishing != null) return@map publishing

            // Priority 2: Actively processing (RECORDING/PROCESSING)
            val processing = list.filter { 
                val sync = it.uploadTask?.status ?: it.draft.status.publication
                it.draft.lifecycle == ArtifactLifecycle.PROCESSING || 
                sync is SyncStatus.Uploading || 
                sync is SyncStatus.Finalizing 
            }.maxByOrNull { it.draft.updatedAt }
            
            processing
        }
    }

    fun observeAllUploadTasks(): Flow<List<UploadTaskEntity>> = uploadTaskDao.observeAllTasks()

    /**
     * Combined flow for UI to show drafts with their active upload progress.
     */
    fun observeDraftsWithUploads(): Flow<List<DraftWithUpload>> {
        return combine(observeDrafts(), observeAllUploadTasks()) { drafts, tasks ->
            val taskMap = tasks.associateBy { it.draftId }
            drafts.map { DraftWithUpload(it, taskMap[it.id]) }
        }
    }

    fun observeActiveDraftsWithUploads(): Flow<List<DraftWithUpload>> {
        return combine(observeActiveDrafts(), observeAllUploadTasks()) { drafts, tasks ->
            val taskMap = tasks.associateBy { it.draftId }
            drafts.map { DraftWithUpload(it, taskMap[it.id]) }
        }
    }

    fun observePublishingDraftsWithUploads(): Flow<List<DraftWithUpload>> {
        return combine(observePublishingDrafts(), observeAllUploadTasks()) { drafts, tasks ->
            val taskMap = tasks.associateBy { it.draftId }
            drafts.map { DraftWithUpload(it, taskMap[it.id]) }
        }
    }

    suspend fun prepareForPublishing(draftId: String, initialStatus: SyncStatus): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = userRepository.getCurrentUserId() ?: return@withContext Result.failure(AppError.Unauthenticated())
            draftsDatabase.withTransaction {
                val draft = draftDao.getDraftById(draftId, userId) ?: throw Exception("Draft not found")
                
                // 1. Calculate actual size for early progress accuracy
                val actualSize = File(draft.frozenAudioPath ?: draft.localAudioPath).length()

                // 2. Update Draft lifecycle to locking state
                draftDao.updateStatusAndLifecycle(draftId, userId, draft.status.copy(publication = initialStatus), ArtifactLifecycle.READY_TO_PUBLISH)

                // Sync the actual size to the main draft table too
                draftDao.updateSyncProgress(draftId, userId, 0, actualSize, draft.uploadSessionUri)
                
                // 3. Initialize the separated upload task
                uploadTaskDao.insert(UploadTaskEntity(
                    draftId = draftId,
                    workerId = null,
                    status = initialStatus,
                    uploadedBytes = 0,
                    totalBytes = actualSize,
                    sessionUri = draft.uploadSessionUri,
                    audioUrl = draft.uploadedAudioUrl
                ))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateDraft(draftId: String, transform: (ArtifactDraftEntity) -> ArtifactDraftEntity): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = userRepository.getCurrentUserId() ?: return@withContext Result.failure(AppError.Unauthenticated())
            draftsDatabase.withTransaction {
                draftDao.getDraftById(draftId, userId)?.let { draft ->
                    val updated = transform(draft)
                    draftDao.update(updated)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateStatus(draftId: String, transform: (DraftStatus) -> DraftStatus): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = userRepository.getCurrentUserId() ?: return@withContext Result.failure(AppError.Unauthenticated())
            draftsDatabase.withTransaction {
                draftDao.getDraftById(draftId, userId)?.let { draft ->
                    val newStatus = transform(draft.status)
                    draftDao.updateStatus(draftId, userId, newStatus)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateUploadProgress(draftId: String, uploaded: Long, total: Long, sessionUri: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = userRepository.getCurrentUserId() ?: return@withContext Result.failure(AppError.Unauthenticated())
            draftsDatabase.withTransaction {
                uploadTaskDao.updateProgress(draftId, uploaded, total, sessionUri)
                draftDao.updateSyncProgress(draftId, userId, uploaded, total, sessionUri)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateUploadStatus(draftId: String, status: SyncStatus): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = userRepository.getCurrentUserId() ?: return@withContext Result.failure(AppError.Unauthenticated())
            draftsDatabase.withTransaction {
                uploadTaskDao.updateStatus(draftId, status)
                // Synchronize with Draft status for UI observers that look at the draft directly
                draftDao.getDraftById(draftId, userId)?.let { draft ->
                    draftDao.updateStatus(draftId, userId, draft.status.copy(publication = status))
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateUploadedAudioUrl(draftId: String, url: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = userRepository.getCurrentUserId() ?: return@withContext Result.failure(AppError.Unauthenticated())
            draftsDatabase.withTransaction {
                draftDao.updateUploadCheckpoint(draftId, userId, url)
                uploadTaskDao.updateAudioUrl(draftId, url)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun markAsPublished(draftId: String, remoteId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = userRepository.getCurrentUserId() ?: return@withContext Result.failure(AppError.Unauthenticated())
            draftsDatabase.withTransaction {
                draftDao.markAsPublished(draftId, userId, remoteId)
                uploadTaskDao.deleteByDraftId(draftId)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }
}

data class DraftWithUpload(
    val draft: ArtifactDraftEntity,
    val uploadTask: UploadTaskEntity?
)

/**
 * Internal key used to detect structural changes relevant to playback.
 * Filtering based on this key prevents redundant transcript decoding during
 * high-frequency progress updates.
 */
private data class PlaybackKey(
    val id: String?,
    val title: String?,
    val transcriptContent: String?,
    val emotion: Emotion?,
    val durationMs: Long,
    val amplitudeData: List<Float>,
    val lifecycle: ArtifactLifecycle?
) {
    companion object {
        fun from(entity: ArtifactDraftEntity?): PlaybackKey? {
            if (entity == null) return null
            return PlaybackKey(
                id = entity.id,
                title = entity.title,
                // We use the unsecure string for comparison as SecureString doesn't override equals
                transcriptContent = entity.transcriptSegmentsJson?.toUnsecureString(),
                emotion = entity.emotion,
                durationMs = entity.durationMs,
                amplitudeData = entity.amplitudeData,
                lifecycle = entity.lifecycle
            )
        }
    }
}
