package com.saurabh.artifact.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playback_positions")
data class PlaybackPosition(
    @PrimaryKey val artifactId: String,
    val positionMs: Long,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface PlaybackPositionDao {
    @Query("SELECT * FROM playback_positions WHERE artifactId = :artifactId")
    suspend fun getPosition(artifactId: String): PlaybackPosition?

    @Query("SELECT * FROM playback_positions")
    fun observeAllPositions(): Flow<List<PlaybackPosition>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePosition(position: PlaybackPosition)

    @Query("DELETE FROM playback_positions WHERE artifactId = :artifactId")
    suspend fun deletePosition(artifactId: String)
}
