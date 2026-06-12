package com.saurabh.artifact.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "current_user_profile")
data class UserLocalEntity(
    @PrimaryKey val id: String,
    val anonymousId: String,
    val anonymousName: String,
    val anonymousSigil: String,
    val avatarSeed: String,
    val avatarColor: String,
    val avatarConfigJson: String,
    val lastUpdated: Long = System.currentTimeMillis()
)
