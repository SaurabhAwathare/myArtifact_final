package com.saurabh.artifact.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "current_user_profile")
data class UserLocalEntity(
    @PrimaryKey val id: String,
    val anonymousId: String,
    val anonymousName: String,
    val anonymousSigil: String,
    @ColumnInfo(name = "avatarSeed") val sigilSeed: String,
    @ColumnInfo(name = "avatarColor") val sigilColor: String,
    @ColumnInfo(name = "avatarConfigJson") val sigilConfigJson: String,
    val lastUpdated: Long = System.currentTimeMillis()
)
