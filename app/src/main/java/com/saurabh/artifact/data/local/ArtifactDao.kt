package com.saurabh.artifact.data.local

import androidx.paging.PagingSource
import androidx.room.*

@Dao
interface ArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(artifacts: List<ArtifactEntity>)

    /**
     * Fetches a paginated stream of all artifacts from the local cache, excluding suppressed, reported, and ignored items.
     */
    @Query("""
        SELECT * FROM artifacts 
        WHERE (recommendationState != 'SUPPRESSED' 
          AND id NOT IN (SELECT artifactId FROM reported_artifacts WHERE userId = :currentUserId)
          AND authorAnonymousId NOT IN (SELECT userId FROM ignored_users WHERE ownerUserId = :currentUserId))
        ORDER BY createdAt DESC, id DESC
    """)
    fun getArtifactsPaged(currentUserId: String): PagingSource<Int, ArtifactEntity>

    /**
     * Fetches a paginated stream of artifacts filtered by specific emotions and safety rules.
     */
    @Query("""
        SELECT * FROM artifacts 
        WHERE (recommendationState != 'SUPPRESSED' 
          AND id NOT IN (SELECT artifactId FROM reported_artifacts WHERE userId = :currentUserId)
          AND authorAnonymousId NOT IN (SELECT userId FROM ignored_users WHERE ownerUserId = :currentUserId))
        AND (emotion IN (:emotions))
        ORDER BY createdAt DESC, id DESC
    """)
    fun getArtifactsPagedFiltered(currentUserId: String, emotions: List<com.saurabh.artifact.model.Emotion>): PagingSource<Int, ArtifactEntity>

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
        WHERE userId = :userId AND authorAnonymousId = :anonymousId
    """)
    suspend fun updateAuthorInfo(
        userId: String, 
        anonymousId: String,
        name: String, 
        sigil: String, 
        seed: String, 
        color: String, 
        configJson: String,
        identityVersion: Long
    )
}
