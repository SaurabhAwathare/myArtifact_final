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

    @Query("SELECT EXISTS(SELECT 1 FROM ignored_users WHERE userId = :userId)")
    suspend fun isIgnored(userId: String): Boolean

    @Query("SELECT userId FROM ignored_users")
    suspend fun getAllIgnoredUserIds(): List<String>

    @Query("SELECT userId FROM ignored_users")
    fun observeAllIgnoredUserIds(): Flow<List<String>>

    @Query("DELETE FROM ignored_users WHERE userId = :userId")
    suspend fun delete(userId: String)

    @Query("DELETE FROM ignored_users")
    suspend fun deleteAll()
}
