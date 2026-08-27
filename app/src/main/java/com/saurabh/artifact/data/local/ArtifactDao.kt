package com.saurabh.artifact.data.local

import androidx.paging.PagingSource
import androidx.room.*

@Dao
interface ArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(artifacts: List<ArtifactEntity>)

    /**
     * Fetches a paginated stream of artifacts from the local cache.
     * Optimized to remove O(N^2) absoluteIndex calculation; indexing is now handled 
     * in the repository or UI layer for efficiency.
     */
    @Query("""
        SELECT * FROM artifacts 
        WHERE (recommendationState != 'SUPPRESSED' AND id NOT IN (SELECT artifactId FROM reported_artifacts WHERE userId = :currentUserId))
        AND (:emotions IS NULL OR emotion IN (:emotions))
        ORDER BY createdAt DESC, id DESC
    """)
    fun getArtifactsPaged(currentUserId: String, emotions: List<com.saurabh.artifact.model.Emotion>?): PagingSource<Int, ArtifactEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM artifacts LIMIT 1)")
    suspend fun hasCachedArtifacts(): Boolean

    @Query("DELETE FROM artifacts")
    suspend fun clearAll()

    @Query("SELECT * FROM artifacts WHERE id = :artifactId")
    suspend fun getArtifactById(artifactId: String): ArtifactEntity?

    @Query("SELECT * FROM artifacts WHERE id IN (:ids)")
    suspend fun getArtifactsByIds(ids: List<String>): List<ArtifactEntity>

    @Query("DELETE FROM artifacts WHERE emotion IN (:emotions)")
    suspend fun deleteArtifactsByEmotions(emotions: List<com.saurabh.artifact.model.Emotion>)

    @Query("DELETE FROM artifacts WHERE id = :artifactId")
    suspend fun deleteById(artifactId: String)

    @Query("DELETE FROM artifacts WHERE createdAt < :timestamp")
    suspend fun deleteOldArtifacts(timestamp: Long)

    @Query("""
        UPDATE artifacts 
        SET authorName = :name, 
            authorSigil = :sigil, 
            authorSigilSeed = :seed, 
            authorSigilColor = :color, 
            authorSigilConfigJson = :configJson,
            identityVersion = :identityVersion
        WHERE userId = :userId
    """)
    suspend fun updateAuthorInfo(
        userId: String, 
        name: String, 
        sigil: String, 
        seed: String, 
        color: String, 
        configJson: String,
        identityVersion: Long
    )
}
