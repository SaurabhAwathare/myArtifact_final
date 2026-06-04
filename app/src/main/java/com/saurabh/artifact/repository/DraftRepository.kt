package com.saurabh.artifact.repository

import androidx.room.withTransaction
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.model.*
import com.saurabh.artifact.util.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DraftRepository @Inject constructor(
    private val draftDao: DraftDao,
    private val uploadTaskDao: UploadTaskDao,
    private val draftsDatabase: DraftsDatabase,
    private val storageManager: StorageManager,
    private val deletionManager: com.saurabh.artifact.audio.DraftDeletionManager
) {
    suspend fun getDraft(id: String): ArtifactDraftEntity? = withContext(Dispatchers.IO) {
        draftDao.getDraftById(id)
    }

    fun observeDraft(id: String): Flow<ArtifactDraftEntity?> = draftDao.observeDraftById(id)

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
        }
    }

    fun observeUploadTask(draftId: String): Flow<UploadTaskEntity?> = uploadTaskDao.observeTaskByDraftId(draftId)

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

    suspend fun prepareForPublishing(draftId: String, initialStatus: SyncStatus) = withContext(Dispatchers.IO) {
        draftsDatabase.withTransaction {
            val draft = draftDao.getDraftById(draftId) ?: return@withTransaction
            
            // 1. Update Draft lifecycle to locking state
            updateStatus(draftId) { 
                it.copy(
                    lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
                    publication = initialStatus
                )
            }
            
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
    }

    suspend fun updateStatus(draftId: String, transform: (DraftStatus) -> DraftStatus) = withContext(Dispatchers.IO) {
        draftDao.getDraftById(draftId)?.let { draft ->
            val newStatus = transform(draft.status)
            draftDao.updateStatus(draftId, newStatus)
        }
    }

    suspend fun updateLifecycle(draftId: String, lifecycle: ArtifactLifecycle) = updateStatus(draftId) {
        it.copy(lifecycle = lifecycle)
    }

    suspend fun updateProcessingStatus(draftId: String, processing: ProcessingStatus) = updateStatus(draftId) {
        it.copy(processing = processing)
    }

    suspend fun updateUploadProgress(draftId: String, uploaded: Long, total: Long, sessionUri: String?) = withContext(Dispatchers.IO) {
        uploadTaskDao.updateProgress(draftId, uploaded, total, sessionUri)
    }

    suspend fun updateUploadStatus(draftId: String, status: SyncStatus) = withContext(Dispatchers.IO) {
        draftsDatabase.withTransaction {
            uploadTaskDao.updateStatus(draftId, status)
            // Synchronize with Draft status for UI observers that look at the draft directly
            updateStatus(draftId) { it.copy(publication = status) }
        }
    }

    suspend fun updateUploadedAudioUrl(draftId: String, url: String) = withContext(Dispatchers.IO) {
        draftsDatabase.withTransaction {
            draftDao.updateUploadCheckpoint(draftId, url)
            uploadTaskDao.updateAudioUrl(draftId, url)
        }
    }

    suspend fun markAsPublished(draftId: String, remoteId: String) = withContext(Dispatchers.IO) {
        draftsDatabase.withTransaction {
            draftDao.markAsPublished(draftId, remoteId)
            uploadTaskDao.deleteByDraftId(draftId)
        }
    }

    suspend fun deleteDraftCompletely(draftId: String) = withContext(Dispatchers.IO) {
        deletionManager.deleteDraft(draftId)
    }
}

data class DraftWithUpload(
    val draft: ArtifactDraftEntity,
    val uploadTask: UploadTaskEntity?
)
