package com.saurabh.artifact.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "queued_uploads")
data class QueuedUpload(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val username: String,
    val fileUri: String,
    val title: String,
    val isPublic: Boolean,
    val duration: Long,
    val emotion: String,
    val emotionTag: String = "",
    val emotionConfidence: Float = 0f,
    val userEmoji: String = "✨",
    val prompt: String = "",
    val redactionFilter: String = "",
    val amplitudeDataJson: String, // Store as JSON string or use TypeConverter
    val createdAt: Long = System.currentTimeMillis()
)
