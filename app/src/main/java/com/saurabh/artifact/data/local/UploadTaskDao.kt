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

    @Transaction
    suspend fun tryAcquireOwnership(draftId: String, newOwner: UploadOwner, timeoutThreshold: Long): Boolean {
        val task = getTaskByDraftId(draftId) ?: return false
        val now = System.currentTimeMillis()
        
        // Ownership is available if:
        // 1. It's currently unowned
        // 2. It's already owned by the same component
        // 3. The current ownership has timed out (lastUpdated < timeoutThreshold)
        val canAcquire = task.owner == null || 
                         task.owner == newOwner || 
                         task.lastUpdated < timeoutThreshold
        
        return if (canAcquire) {
            updateOwnership(draftId, newOwner, now)
            true
        } else {
            false
        }
    }

    @Query("UPDATE upload_tasks SET owner = :owner, lastUpdated = :timestamp WHERE draftId = :draftId")
    suspend fun updateOwnership(draftId: String, owner: UploadOwner?, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE upload_tasks SET owner = NULL WHERE draftId = :draftId")
    suspend fun releaseOwnership(draftId: String)
}
