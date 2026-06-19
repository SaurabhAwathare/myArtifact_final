package com.saurabh.artifact.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.data.local.ArtifactEngagement
import com.saurabh.artifact.data.local.EngagementDao
import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.UserArtifactEngagement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.BitSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngagementRepository @Inject constructor(
    private val engagementDao: EngagementDao,
    private val firestore: FirebaseFirestore,
    private val authRepository: AuthRepository
) {

    suspend fun getEngagement(artifactId: String): Result<EngagementEvidence> = withContext(Dispatchers.IO) {
        try {
            val engagement = engagementDao.getEngagement(artifactId)?.toDomain()
            if (engagement != null) {
                Result.success(engagement)
            } else {
                Result.failure(AppError.NotFound("Engagement", artifactId))
            }
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun saveEngagement(evidence: EngagementEvidence): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val entity = evidence.toEntity()
            engagementDao.insertEngagement(entity)
            
            // Sync to cloud if significant (e.g., furthest position updated)
            syncToCloud(entity)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateLastPosition(artifactId: String, positionMs: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            engagementDao.updateLastPosition(artifactId, positionMs)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    private fun syncToCloud(engagement: ArtifactEngagement) {
        val userId = authRepository.currentUserId
        if (userId.isEmpty()) return

        // Calculate authoritative validation state before syncing
        val evidence = engagement.toDomain()
        val policy = com.saurabh.artifact.domain.review.ReviewPolicy()
        val validator = com.saurabh.artifact.audio.validation.DefaultReviewValidator()
        val validationResult = validator.validate(evidence, policy)

        // For now, we only sync a subset of data to Firestore to avoid heavy writes
        val remoteData = UserArtifactEngagement(
            userId = userId,
            artifactId = engagement.artifactId,
            isCommentUnlocked = validationResult.isValid,
            lastPositionMs = engagement.lastPositionMs,
            lastFurthestPosition = engagement.furthestPositionMs,
            totalDurationMs = engagement.durationMs,
            hasReachedEnd = engagement.hasReachedEnd,
            updatedAt = engagement.lastUpdated
        )


        try {
            val userIdPath = "/users/$userId/engagement/${engagement.artifactId}"
            android.util.Log.d("EngagementRepository", "Syncing engagement to: $userIdPath")
            
            firestore.collection("users").document(userId)
                .collection("engagement").document(engagement.artifactId)
                .set(remoteData)
        } catch (e: Exception) {
            android.util.Log.e("EngagementRepository", "Sync to cloud failed", e)
        }
    }

    private fun ArtifactEngagement.toDomain(): EngagementEvidence {
        return EngagementEvidence(
            artifactId = artifactId,
            versionTag = versionTag,
            durationMs = durationMs,
            audioChecksum = audioChecksum,
            coverage = BitSet.valueOf(coverage),
            lastPositionMs = lastPositionMs,
            furthestPositionMs = furthestPositionMs,
            hasReachedEnd = hasReachedEnd,
            lastUpdated = lastUpdated
        )
    }

    private fun EngagementEvidence.toEntity(): ArtifactEngagement {
        return ArtifactEngagement(
            artifactId = artifactId,
            versionTag = versionTag,
            durationMs = durationMs,
            audioChecksum = audioChecksum,
            coverage = coverage.toByteArray(),
            lastPositionMs = lastPositionMs,
            furthestPositionMs = furthestPositionMs,
            hasReachedEnd = hasReachedEnd,
            lastUpdated = lastUpdated
        )
    }
}
