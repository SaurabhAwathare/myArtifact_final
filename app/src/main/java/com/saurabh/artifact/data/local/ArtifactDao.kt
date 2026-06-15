package com.saurabh.artifact.data.local

import androidx.paging.PagingSource
import androidx.room.*

@Dao
interface ArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(artifacts: List<ArtifactEntity>)

    @Query("""
        SELECT * FROM artifacts 
        WHERE (reportCount < 3 AND safetyConcernCount < 3 AND reporterIds NOT LIKE :userIdPattern)
        ORDER BY createdAt DESC
    """)
    fun getArtifactsPaged(userIdPattern: String): PagingSource<Int, ArtifactEntity>

    @Query("DELETE FROM artifacts")
    suspend fun clearAll()

    @Query("SELECT * FROM artifacts WHERE id = :artifactId")
    suspend fun getArtifactById(artifactId: String): ArtifactEntity?

    @Query("SELECT * FROM artifacts WHERE id IN (:ids)")
    suspend fun getArtifactsByIds(ids: List<String>): List<ArtifactEntity>

    @Query("DELETE FROM artifacts WHERE id = :artifactId")
    suspend fun deleteById(artifactId: String)

    @Query("DELETE FROM artifacts WHERE createdAt < :timestamp")
    suspend fun deleteOldArtifacts(timestamp: Long)
}
