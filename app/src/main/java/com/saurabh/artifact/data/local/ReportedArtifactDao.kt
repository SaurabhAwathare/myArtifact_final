package com.saurabh.artifact.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReportedArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reportedArtifact: ReportedArtifactEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM reported_artifacts WHERE userId = :userId AND artifactId = :artifactId)")
    suspend fun isReported(userId: String, artifactId: String): Boolean

    @Query("SELECT artifactId FROM reported_artifacts WHERE userId = :userId")
    suspend fun getReportedArtifactIds(userId: String): List<String>

    @Query("DELETE FROM reported_artifacts WHERE userId = :userId AND artifactId = :artifactId")
    suspend fun delete(userId: String, artifactId: String)
}
