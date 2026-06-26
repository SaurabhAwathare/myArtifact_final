package com.saurabh.artifact.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dead_letter_interactions")
data class DeadLetterInteractionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val originalId: Long,
    val artifactId: String,
    val interactionType: String,
    val action: String,
    val metadata: String? = null,
    val createdAt: Long,
    val correlationId: String,
    val failedAt: Long = System.currentTimeMillis(),
    val failureReason: String?,
    val failureType: String, // "PERMANENT" or "RETRY_LIMIT_EXCEEDED"
    val retryCount: Int
)
