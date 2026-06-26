package com.saurabh.artifact.repository

import com.google.gson.Gson
import com.saurabh.artifact.data.local.ArtifactEngagement
import com.saurabh.artifact.data.local.EngagementDao
import com.saurabh.artifact.data.local.InteractionAction
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.data.local.PendingInteractionEntity
import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.domain.review.EngagementSyncPayload
import com.saurabh.artifact.model.AppError
import android.util.Base64
import androidx.work.*
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.domain.auth.SessionConstants
import com.saurabh.artifact.worker.InteractionSyncWorker
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.BitSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngagementRepository @Inject constructor(
    private val engagementDao: EngagementDao,
    private val authRepository: AuthRepository,
    private val pendingInteractionDao: PendingInteractionDao,
    private val workManager: WorkManager,
    private val firestore: FirebaseFirestore,
    private val gson: Gson
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

    private suspend fun syncToCloud(engagement: ArtifactEngagement) {
        val userId = authRepository.currentUserId
        if (userId.isEmpty()) return

        val payload = EngagementSyncPayload(
            artifactId = engagement.artifactId,
            lastPositionMs = engagement.lastPositionMs,
            furthestPositionMs = engagement.furthestPositionMs,
            durationMs = engagement.durationMs,
            hasReachedEnd = engagement.hasReachedEnd,
            coverage = Base64.encodeToString(engagement.coverage, Base64.NO_WRAP),
            lastUpdated = engagement.lastUpdated
        )

        val pending = PendingInteractionEntity(
            userId = userId,
            artifactId = engagement.artifactId,
            interactionType = InteractionType.ENGAGEMENT,
            action = InteractionAction.ADD, // Action is arbitrary for engagement
            metadata = gson.toJson(payload)
        )

        // Replace existing pending engagement for this artifact to avoid queue bloat
        pendingInteractionDao.deleteByType(engagement.artifactId, userId, InteractionType.ENGAGEMENT)
        pendingInteractionDao.insert(pending)

        enqueueSyncWorker()
    }

    /**
     * Internal synchronization method for engagement evidence.
     * INTERNAL SYNC API: Intended exclusively for InteractionSyncWorker.
     * Performs direct Firestore write without enqueuing.
     */
    internal suspend fun syncEngagementToFirestore(userId: String, payload: EngagementSyncPayload): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val coverageBytes = Base64.decode(payload.coverage, Base64.NO_WRAP)
            
            val updates = mapOf(
                "userId" to userId,
                "artifactId" to payload.artifactId,
                "lastPositionMs" to payload.lastPositionMs,
                "lastFurthestPosition" to payload.furthestPositionMs,
                "totalDurationMs" to payload.durationMs,
                "hasReachedEnd" to payload.hasReachedEnd,
                "coverage" to coverageBytes,
                "updatedAt" to payload.lastUpdated
            )

            firestore.collection("users").document(userId)
                .collection("engagement").document(payload.artifactId)
                .set(updates, com.google.firebase.firestore.SetOptions.merge())
                .await()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun enqueueSyncWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<InteractionSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(InteractionSyncWorker.TAG)
            .addTag(SessionConstants.TAG_USER_SESSION_WORK)
            .build()

        workManager.enqueueUniqueWork(
            InteractionSyncWorker.TAG,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
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
