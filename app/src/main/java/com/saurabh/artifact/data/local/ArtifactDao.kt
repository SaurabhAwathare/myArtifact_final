package com.saurabh.artifact.data.local

import androidx.paging.PagingSource
import androidx.room.*

@Dao
interface ArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(artifacts: List<ArtifactEntity>)

    @Query("SELECT * FROM artifacts ORDER BY createdAt DESC")
    fun getArtifactsPaged(): PagingSource<Int, ArtifactEntity>

    @Query("DELETE FROM artifacts")
    suspend fun clearAll()

    @Query("SELECT * FROM artifacts WHERE id = :artifactId")
    suspend fun getArtifactById(artifactId: String): ArtifactEntity?

    @Query("DELETE FROM artifacts WHERE id = :artifactId")
    suspend fun deleteById(artifactId: String)

    @Query("DELETE FROM artifacts WHERE createdAt < :timestamp")
    suspend fun deleteOldArtifacts(timestamp: Long)
}
