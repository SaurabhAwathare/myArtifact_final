package com.saurabh.artifact.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.data.local.ArtifactEngagement
import com.saurabh.artifact.data.local.EngagementDao
import com.saurabh.artifact.domain.review.EngagementEvidence
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

    suspend fun getEngagement(artifactId: String): EngagementEvidence? {
        return engagementDao.getEngagement(artifactId)?.toDomain()
    }

    suspend fun saveEngagement(evidence: EngagementEvidence) {
        withContext(Dispatchers.IO) {
            val entity = evidence.toEntity()
            engagementDao.insertEngagement(entity)
            
            // Sync to cloud if significant (e.g., furthest position updated)
            syncToCloud(entity)
        }
    }

    suspend fun updateLastPosition(artifactId: String, positionMs: Long) {
        withContext(Dispatchers.IO) {
            engagementDao.updateLastPosition(artifactId, positionMs)
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
            lastFurthestPosition = engagement.furthestPositionMs,
            updatedAt = engagement.lastUpdated
        )

        try {
            firestore.collection("users").document(userId)
                .collection("engagement").document(engagement.artifactId)
                .set(remoteData)
        } catch (_: Exception) {
            // Log error or handle offline sync
        }
    }

    private fun ArtifactEngagement.toDomain(): EngagementEvidence {
        return EngagementEvidence(
            artifactId = artifactId,
            versionTag = versionTag,
            durationMs = durationMs,
            audioChecksum = audioChecksum,
            coverage = BitSet.valueOf(coverage),
            effortMap = effortMap,
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
            effortMap = effortMap,
            lastPositionMs = lastPositionMs,
            furthestPositionMs = furthestPositionMs,
            hasReachedEnd = hasReachedEnd,
            lastUpdated = lastUpdated
        )
    }
}
