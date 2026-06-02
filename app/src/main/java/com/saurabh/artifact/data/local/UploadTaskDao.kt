package com.saurabh.artifact.data.local

import androidx.room.*
import com.saurabh.artifact.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: UploadTaskEntity)

    @Update
    suspend fun update(task: UploadTaskEntity)

    @Query("SELECT * FROM upload_tasks WHERE draftId = :draftId")
    suspend fun getTaskByDraftId(draftId: String): UploadTaskEntity?

    @Query("SELECT * FROM upload_tasks WHERE draftId = :draftId")
    fun observeTaskByDraftId(draftId: String): Flow<UploadTaskEntity?>

    @Query("SELECT * FROM upload_tasks")
    fun observeAllTasks(): Flow<List<UploadTaskEntity>>

    @Query("UPDATE upload_tasks SET status = :status, lastUpdated = :timestamp WHERE draftId = :draftId")
    suspend fun updateStatus(draftId: String, status: SyncStatus, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE upload_tasks SET uploadedBytes = :uploadedBytes, totalBytes = :totalBytes, sessionUri = :sessionUri, lastUpdated = :timestamp WHERE draftId = :draftId")
    suspend fun updateProgress(draftId: String, uploadedBytes: Long, totalBytes: Long, sessionUri: String?, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE upload_tasks SET audioUrl = :audioUrl, lastUpdated = :timestamp WHERE draftId = :draftId")
    suspend fun updateAudioUrl(draftId: String, audioUrl: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM upload_tasks")
    suspend fun getAllTasks(): List<UploadTaskEntity>

    @Query("DELETE FROM upload_tasks WHERE draftId = :draftId")
    suspend fun deleteByDraftId(draftId: String)
}
