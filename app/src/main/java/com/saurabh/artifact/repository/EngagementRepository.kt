package com.saurabh.artifact.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.data.local.ArtifactEngagement
import com.saurabh.artifact.data.local.EngagementDao
import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.model.UserArtifactEngagement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    fun observeEngagement(artifactId: String): Flow<EngagementEvidence?> {
        return engagementDao.observeEngagement(artifactId).map { it?.toDomain() }
    }

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

    private suspend fun syncToCloud(engagement: ArtifactEngagement) {
        val userId = authRepository.currentUserId
        if (userId.isEmpty()) return

        // For now, we only sync a subset of data to Firestore to avoid heavy writes
        val remoteData = UserArtifactEngagement(
            userId = userId,
            artifactId = engagement.artifactId,
            isCommentUnlocked = engagement.hasReachedEnd, // Simplified for now
            lastFurthestPosition = engagement.furthestPositionMs,
            updatedAt = engagement.lastUpdated
        )

        try {
            firestore.collection("users").document(userId)
                .collection("engagement").document(engagement.artifactId)
                .set(remoteData)
        } catch (e: Exception) {
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
