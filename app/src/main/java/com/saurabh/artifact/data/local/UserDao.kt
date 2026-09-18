package com.saurabh.artifact.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: UserLocalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfiles(profiles: List<UserLocalEntity>)

    @Query("SELECT * FROM current_user_profile WHERE id = :userId OR anonymousId = :userId ORDER BY lastUpdated DESC LIMIT 1")
    suspend fun getProfile(userId: String): UserLocalEntity?

    @Query("SELECT * FROM current_user_profile WHERE id IN (:userIds) OR anonymousId IN (:userIds)")
    suspend fun getProfiles(userIds: List<String>): List<UserLocalEntity>

    @Query("SELECT * FROM current_user_profile WHERE id = :userId OR anonymousId = :userId ORDER BY lastUpdated DESC LIMIT 1")
    fun observeProfile(userId: String): Flow<UserLocalEntity?>

    @Query("DELETE FROM current_user_profile WHERE id = :key OR (:anonymousId != '' AND id = :anonymousId) OR anonymousId = :key OR (:anonymousId != '' AND anonymousId = :anonymousId)")
    suspend fun deleteProfileByKey(key: String, anonymousId: String = "")

    @Query("DELETE FROM current_user_profile")
    suspend fun clear()
}

