package com.saurabh.artifact.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface IgnoredUserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(ignoredUser: IgnoredUserEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM ignored_users WHERE userId = :userId AND ownerUserId = :ownerUserId)")
    suspend fun isIgnored(userId: String, ownerUserId: String): Boolean

    @Query("SELECT userId FROM ignored_users WHERE ownerUserId = :ownerUserId")
    suspend fun getAllIgnoredUserIds(ownerUserId: String): List<String>

    @Query("SELECT userId FROM ignored_users WHERE ownerUserId = :ownerUserId")
    fun observeAllIgnoredUserIds(ownerUserId: String): Flow<List<String>>

    @Query("DELETE FROM ignored_users WHERE userId = :userId AND ownerUserId = :ownerUserId")
    suspend fun delete(userId: String, ownerUserId: String)

    @Query("DELETE FROM ignored_users WHERE ownerUserId = :ownerUserId")
    suspend fun deleteAll(ownerUserId: String)
}
