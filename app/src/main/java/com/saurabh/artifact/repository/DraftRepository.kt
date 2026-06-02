package com.saurabh.artifact.repository

import androidx.room.withTransaction
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.model.*
import com.saurabh.artifact.util.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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

    suspend fun prepareForPublishing(draftId: String, initialStatus: SyncStatus) = withContext(Dispatchers.IO) {
        draftsDatabase.withTransaction {
            val draft = draftDao.getDraftById(draftId) ?: return@withTransaction
            
            // 1. Update Draft lifecycle to locking state
            draftDao.updateStatus(draftId, draft.status.copy(
                lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
                sync = initialStatus
            ))
            
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

    suspend fun updateUploadProgress(draftId: String, uploaded: Long, total: Long, sessionUri: String?) = withContext(Dispatchers.IO) {
        uploadTaskDao.updateProgress(draftId, uploaded, total, sessionUri)
    }

    suspend fun updateUploadStatus(draftId: String, status: SyncStatus) = withContext(Dispatchers.IO) {
        uploadTaskDao.updateStatus(draftId, status)
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
