package com.saurabh.artifact.repository

import com.saurabh.artifact.data.local.ArtifactEngagement
import com.saurabh.artifact.data.local.EngagementDao
import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.SyncState
import com.saurabh.artifact.worker.EngagementSyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.BitSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngagementRepository @Inject constructor(
    private val engagementDao: EngagementDao,
    private val syncScheduler: EngagementSyncScheduler
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

    suspend fun getEngagementsRequiringSync(): List<EngagementEvidence> = withContext(Dispatchers.IO) {
        engagementDao.getEngagementsRequiringSync().map { it.toDomain() }
    }

    suspend fun updateSyncStatus(artifactId: String, state: SyncState, error: String? = null) = withContext(Dispatchers.IO) {
        engagementDao.updateSyncStatus(artifactId, state, System.currentTimeMillis(), error)
    }

    suspend fun markEngagementSynced(artifactId: String) = withContext(Dispatchers.IO) {
        engagementDao.markAsSynced(artifactId, System.currentTimeMillis())
    }

    fun observeEngagementEvidence(artifactId: String): Flow<EngagementEvidence?> {
        return engagementDao.observeEngagement(artifactId).map { it?.toDomain() }
    }

    suspend fun saveEngagement(evidence: EngagementEvidence): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val entity = evidence.toEntity().copy(syncState = SyncState.PENDING)
            engagementDao.insertEngagement(entity)
            syncScheduler.scheduleSync()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateLastPosition(artifactId: String, positionMs: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            engagementDao.updateLastPosition(artifactId, positionMs)
            syncScheduler.scheduleSync()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
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
