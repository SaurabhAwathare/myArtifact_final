package com.saurabh.artifact.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: UserLocalEntity)

    @Query("SELECT * FROM current_user_profile WHERE id = :userId")
    suspend fun getProfile(userId: String): UserLocalEntity?

    @Query("DELETE FROM current_user_profile")
    suspend fun clear()
}
