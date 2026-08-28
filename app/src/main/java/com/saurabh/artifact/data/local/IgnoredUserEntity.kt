package com.saurabh.artifact.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a Presence (User) that the current user has chosen to privately ignore.
 * This entity is used to filter the discovery feed locally.
 */
@Entity(tableName = "ignored_users")
data class IgnoredUserEntity(
    @PrimaryKey val userId: String,
    val createdAt: Long = System.currentTimeMillis()
)
