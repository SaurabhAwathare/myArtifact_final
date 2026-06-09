package com.saurabh.artifact.repository

import androidx.room.withTransaction
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DraftRepository @Inject constructor(
    private val draftDao: DraftDao,
    private val uploadTaskDao: UploadTaskDao,
    private val draftsDatabase: AppDatabase
) {
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

    fun observeDrafts(): Flow<List<ArtifactDraftEntity>> = draftDao.observeDrafts()

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
            draftsDatabase.withTransaction {
                val draft = draftDao.getDraftById(draftId) ?: throw Exception("Draft not found")
                
                // 1. Update Draft lifecycle to locking state
                updateStatus(draftId) { 
                    it.copy(
                        lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
                        publication = initialStatus
                    )
                }.getOrThrow()
                
                // 2. Initialize the separated upload task
                uploadTaskDao.insert(UploadTaskEntity(
                    draftId = draftId,
                    workerId = null,
                    status = initialStatus,
                    uploadedBytes = 0,
                    totalBytes = draft.totalBytes,
                    sessionUri = draft.uploadSessionUri,
                    audioUrl = draft.uploadedAudioUrl
                ))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateStatus(draftId: String, transform: (DraftStatus) -> DraftStatus): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftsDatabase.withTransaction {
                draftDao.getDraftById(draftId)?.let { draft ->
                    val newStatus = transform(draft.status)
                    draftDao.updateStatus(draftId, newStatus)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateUploadProgress(draftId: String, uploaded: Long, total: Long, sessionUri: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            uploadTaskDao.updateProgress(draftId, uploaded, total, sessionUri)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateUploadStatus(draftId: String, status: SyncStatus): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftsDatabase.withTransaction {
                uploadTaskDao.updateStatus(draftId, status)
                // Synchronize with Draft status for UI observers that look at the draft directly
                updateStatus(draftId) { it.copy(publication = status) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateUploadedAudioUrl(draftId: String, url: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftsDatabase.withTransaction {
                draftDao.updateUploadCheckpoint(draftId, url)
                uploadTaskDao.updateAudioUrl(draftId, url)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun markAsPublished(draftId: String, remoteId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            draftsDatabase.withTransaction {
                draftDao.markAsPublished(draftId, remoteId)
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
