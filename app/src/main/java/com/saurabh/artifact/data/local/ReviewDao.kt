package com.saurabh.artifact.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewDao {
    @Query("SELECT * FROM artifact_review_evidence WHERE artifactId = :artifactId")
    suspend fun getEvidence(artifactId: String): ArtifactReviewEvidence?

    @Query("SELECT * FROM artifact_review_evidence WHERE artifactId = :artifactId")
    fun observeEvidence(artifactId: String): Flow<ArtifactReviewEvidence?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvidence(evidence: ArtifactReviewEvidence)

    @Query("DELETE FROM artifact_review_evidence WHERE artifactId = :artifactId")
    suspend fun deleteEvidence(artifactId: String)
}
