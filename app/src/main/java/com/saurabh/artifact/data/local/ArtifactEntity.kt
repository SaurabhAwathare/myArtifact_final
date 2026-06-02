package com.saurabh.artifact.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "artifacts")
data class ArtifactEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val authorAnonymousId: String,
    val authorName: String,
    val authorSigil: String,
    val authorAvatarSeed: String,
    val authorAvatarColor: String,
    val authorAvatarConfigJson: String,
    val audioUrl: String,
    val createdAt: Long,
    val durationMs: Long,
    val title: String,
    val description: String,
    val emotion: String,
    val emotionTag: String,
    val playCount: Int,
    val reactionCount: Int,
    val commentCount: Int,
    val amplitudeData: List<Float>,
    val transcriptUrl: String? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)
