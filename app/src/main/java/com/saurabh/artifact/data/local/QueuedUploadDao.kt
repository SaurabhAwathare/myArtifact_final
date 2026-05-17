package com.saurabh.artifact.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface QueuedUploadDao {
    @Insert
    suspend fun insert(upload: QueuedUpload)

    @Query("SELECT * FROM queued_uploads ORDER BY createdAt ASC")
    suspend fun getAllQueuedUploads(): List<QueuedUpload>

    @Delete
    suspend fun delete(upload: QueuedUpload)
}
