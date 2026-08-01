package com.saurabh.artifact.repository

import com.google.firebase.auth.FirebaseAuth
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.di.ApplicationScope
import com.saurabh.artifact.data.local.ArtifactEngagement
import com.saurabh.artifact.data.local.EngagementDao
import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.domain.review.ReviewTrackingVersion
import com.saurabh.artifact.domain.review.EngagementState
import com.saurabh.artifact.domain.review.UnlockStatus
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.SyncState
import com.saurabh.artifact.worker.EngagementSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.BitSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngagementRepository @Inject constructor(
    private val engagementDao: EngagementDao,
    private val firestoreRepository: FirestoreEngagementRepository,
    private val syncScheduler: EngagementSyncScheduler,
    private val diagnosticLogger: DiagnosticLogger,
    @ApplicationScope private val externalScope: CoroutineScope
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

    suspend fun reclaimOrphanedSyncs(): Int = withContext(Dispatchers.IO) {
        engagementDao.reclaimOrphanedSyncs()
    }

    suspend fun updateSyncStatus(artifactId: String, state: SyncState, error: String? = null) = withContext(Dispatchers.IO) {
        engagementDao.updateSyncStatus(artifactId, state, System.currentTimeMillis(), error)
    }

    suspend fun markEngagementSynced(artifactId: String): Int = withContext(Dispatchers.IO) {
        engagementDao.markAsSynced(artifactId, System.currentTimeMillis())
    }

    /**
     * Forces a re-sync of engagement evidence by resetting the local sync state to PENDING.
     * Use when backend verification times out.
     */
    suspend fun forceRetrySync(artifactId: String) = withContext(Dispatchers.IO) {
        engagementDao.updateSyncStatus(artifactId, SyncState.PENDING, System.currentTimeMillis(), "Retry triggered by timeout")
        syncScheduler.scheduleSync()
    }

    /**
     * Observes engagement evidence, combining local sync state with remote authoritative unlock status.
     */
    fun observeEngagementEvidence(artifactId: String): Flow<EngagementEvidence?> {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
            ?: return engagementDao.observeEngagement(artifactId).map { it?.toDomain() }

        val localFlow = engagementDao.observeEngagement(artifactId).distinctUntilChanged()
        val remoteFlow = firestoreRepository.observeRemoteUnlockStatus(currentUserId, artifactId)
            .onEach { remote ->
                if (remote != null) {
                    externalScope.launch {
                        updateLocalUnlockCache(artifactId, remote)
                    }
                }
            }

        return combine(localFlow, remoteFlow) { local, remote ->
            if (local == null) return@combine null

            local.toDomain().copy(
                unlockStatus = remote ?: UnlockStatus(
                    isCommentUnlocked = local.isCommentUnlocked,
                    unlockTimestamp = local.unlockTimestamp,
                    engagementState = EngagementState.fromString(local.engagementState),
                    unlockReason = local.unlockReason
                )
            )
        }
    }

    private suspend fun updateLocalUnlockCache(artifactId: String, remote: UnlockStatus) {
        withContext(Dispatchers.IO) {
            engagementDao.updateUnlockStatus(
                artifactId = artifactId,
                isUnlocked = remote.isCommentUnlocked,
                timestamp = remote.unlockTimestamp,
                state = remote.engagementState.name,
                reason = remote.unlockReason,
                remoteUpdated = remote.updatedAt
            )
        }
    }

    suspend fun saveEngagement(evidence: EngagementEvidence): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val entity = evidence.toEntity().copy(syncState = SyncState.PENDING)
            engagementDao.insertEngagementMonotonic(entity)
            syncScheduler.scheduleSync()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun updateLastPosition(artifactId: String, positionMs: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val exists = engagementDao.getEngagement(artifactId) != null
            val rowsUpdated = engagementDao.updateLastPosition(artifactId, positionMs)

            diagnosticLogger.info(
                DiagnosticCategory.DATABASE,
                "INVESTIGATION_LOG",
                mapOf(
                    "TRACE_ID" to artifactId,
                    "Stage" to "RoomUpdate",
                    "ExistsBeforeUpdate" to exists,
                    "RowsUpdated" to rowsUpdated,
                    "PendingSync" to (rowsUpdated > 0)
                )
            )

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
            lastUpdated = lastUpdated,
            reviewTrackingVersion = ReviewTrackingVersion.fromInt(reviewTrackingVersion),
            segmentSizeMs = segmentSizeMs,
            unlockStatus = UnlockStatus(
                isCommentUnlocked = isCommentUnlocked,
                unlockTimestamp = unlockTimestamp,
                engagementState = EngagementState.fromString(engagementState),
                unlockReason = unlockReason,
                updatedAt = remoteUpdatedAt
            ),
            syncState = syncState
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
            lastUpdated = lastUpdated,
            reviewTrackingVersion = reviewTrackingVersion.value,
            segmentSizeMs = segmentSizeMs,
            syncState = syncState,
            isCommentUnlocked = unlockStatus.isCommentUnlocked,
            unlockTimestamp = unlockStatus.unlockTimestamp,
            engagementState = unlockStatus.engagementState.name,
            unlockReason = unlockStatus.unlockReason,
            remoteUpdatedAt = unlockStatus.updatedAt
        )
    }
}
