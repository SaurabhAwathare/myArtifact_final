package com.saurabh.artifact.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.saurabh.artifact.model.SyncStatus

@Entity(
    tableName = "upload_tasks",
    foreignKeys = [
        ForeignKey(
            entity = ArtifactDraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("draftId")]
)
data class UploadTaskEntity(
    @PrimaryKey
    val draftId: String,
    val workerId: String?, // ID of the WorkManager worker currently handling this
    val status: SyncStatus = SyncStatus.Queued,
    val uploadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val sessionUri: String? = null,
    val audioUrl: String? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)
