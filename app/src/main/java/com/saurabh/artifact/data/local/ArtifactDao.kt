package com.saurabh.artifact.data.local

import androidx.paging.PagingSource
import androidx.room.*

data class ArtifactEntityWithIndex(
    @Embedded val entity: ArtifactEntity,
    val absoluteIndex: Int
)

@Dao
interface ArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(artifacts: List<ArtifactEntity>)

    @Query("""
        SELECT *, (
            SELECT COUNT(*) FROM artifacts a2 
            WHERE (a2.reportCount < 3 AND a2.safetyConcernCount < 3 AND a2.reporterIds NOT LIKE :userIdPattern)
            AND (a2.createdAt > a1.createdAt OR (a2.createdAt = a1.createdAt AND a2.id >= a1.id))
        ) - 1 as absoluteIndex
        FROM artifacts a1
        WHERE (reportCount < 3 AND safetyConcernCount < 3 AND reporterIds NOT LIKE :userIdPattern)
        ORDER BY createdAt DESC, id DESC
    """)
    fun getArtifactsPaged(userIdPattern: String): PagingSource<Int, ArtifactEntityWithIndex>

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

    @Query("""
        UPDATE artifacts 
        SET authorName = :name, 
            authorSigil = :sigil, 
            authorAvatarSeed = :seed, 
            authorAvatarColor = :color, 
            authorAvatarConfigJson = :configJson 
        WHERE userId = :userId
    """)
    suspend fun updateAuthorInfo(
        userId: String, 
        name: String, 
        sigil: String, 
        seed: String, 
        color: String, 
        configJson: String
    )
}
