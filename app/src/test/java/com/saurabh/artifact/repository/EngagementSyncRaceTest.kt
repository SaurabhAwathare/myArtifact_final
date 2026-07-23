package com.saurabh.artifact.repository

import com.saurabh.artifact.data.local.ArtifactEngagement
import com.saurabh.artifact.data.local.EngagementDao
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.SyncState
import com.saurabh.artifact.worker.EngagementSyncScheduler
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngagementSyncRaceTest {
    private val engagementDao = mockk<EngagementDao>(relaxed = true)
    private val firestoreRepository = mockk<FirestoreEngagementRepository>(relaxed = true)
    private val syncScheduler = mockk<EngagementSyncScheduler>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private val externalScope = mockk<CoroutineScope>(relaxed = true)

    private lateinit var repository: EngagementRepository

    @Before
    fun setup() {
        repository = EngagementRepository(
            engagementDao = engagementDao,
            firestoreRepository = firestoreRepository,
            syncScheduler = syncScheduler,
            diagnosticLogger = diagnosticLogger,
            externalScope = externalScope
        )
    }

    @Test
    fun `Scenario 1 - Normal successful sync transitions to SYNCED`() = runTest {
        val artifactId = "test_artifact"
        
        // 1. Start Sync (State -> SYNCING)
        coEvery { engagementDao.updateSyncStatus(artifactId, SyncState.SYNCING, any(), any()) } just Runs
        repository.updateSyncStatus(artifactId, SyncState.SYNCING)
        
        // 2. Mark as Synced (State -> SYNCED)
        // Mock markAsSynced to return 1 row affected (normal case)
        coEvery { engagementDao.markAsSynced(artifactId, any()) } returns 1
        
        val rowsAffected = repository.markEngagementSynced(artifactId)
        
        assertEquals("Should update 1 row", 1, rowsAffected)
        coVerify { engagementDao.markAsSynced(artifactId, any()) }
    }

    @Test
    fun `Scenario 2 - Race condition, update during upload prevents overwrite`() = runTest {
        val artifactId = "test_artifact"
        
        // 1. Worker starts sync (State -> SYNCING)
        // ... (This happens in the worker, but we simulate repository calls)
        
        // 2. User activity happens while worker is uploading (State -> PENDING)
        // In reality, updateLastPosition sets state to PENDING
        coEvery { engagementDao.updateLastPosition(artifactId, any(), any()) } returns 1
        repository.updateLastPosition(artifactId, 1000L)
        
        // 3. Worker finishes and calls markEngagementSynced
        // DAO will have WHERE syncState = 'SYNCING' guard. 
        // We mock it returning 0 because the state is now PENDING
        coEvery { engagementDao.markAsSynced(artifactId, any()) } returns 0
        
        val rowsAffected = repository.markEngagementSynced(artifactId)
        
        assertEquals("Should update 0 rows due to state guard", 0, rowsAffected)
    }

    @Test
    fun `Scenario 3 - Multiple updates during upload still prevents overwrite`() = runTest {
        val artifactId = "test_artifact"
        
        // 1. Worker starts (SYNCING)
        
        // 2. Multiple user updates (PENDING)
        coEvery { engagementDao.updateLastPosition(artifactId, any(), any()) } returns 1
        repository.updateLastPosition(artifactId, 1000L)
        repository.updateLastPosition(artifactId, 2000L)
        
        // 3. Worker finishes (markAsSynced)
        coEvery { engagementDao.markAsSynced(artifactId, any()) } returns 0
        
        val rowsAffected = repository.markEngagementSynced(artifactId)
        
        assertEquals("Should update 0 rows even with multiple updates", 0, rowsAffected)
    }

    @Test
    fun `Scenario 4 - Next sync cycle recovers correctly`() = runTest {
        val artifactId = "test_artifact"
        
        // 1. Previous sync failed to mark as SYNCED because rowsAffected == 0 (Race happened)
        // 2. Record is still PENDING in DB.
        
        // 3. Next worker run picks up the PENDING record
        val staleEvidence = mockk<ArtifactEngagement>(relaxed = true)
        every { staleEvidence.artifactId } returns artifactId
        every { staleEvidence.syncState } returns SyncState.PENDING
        // Mock toDomain() if needed, but EngagementRepository calls it.
        // EngagementEvidence is a data class, ArtifactEngagement is also a data class.
        // Actually, let's just use real data classes to avoid deep mocking of extension functions.
        val realEntity = ArtifactEngagement(
            artifactId = artifactId,
            syncState = SyncState.PENDING,
            versionTag = "v1",
            durationMs = 0,
            audioChecksum = "",
            coverage = byteArrayOf(),
            lastPositionMs = 0,
            furthestPositionMs = 0,
            hasReachedEnd = false,
            lastUpdated = 0,
            isCommentUnlocked = false,
            unlockTimestamp = null,
            engagementState = "NONE",
            unlockReason = null,
            remoteUpdatedAt = null
        )
        coEvery { engagementDao.getEngagementsRequiringSync() } returns listOf(realEntity)
        
        val pending = repository.getEngagementsRequiringSync()
        assertEquals(1, pending.size)
        assertEquals(artifactId, pending[0].artifactId)
        
        // 4. Next worker succeeds fully
        coEvery { engagementDao.markAsSynced(artifactId, any()) } returns 1
        val rowsAffected = repository.markEngagementSynced(artifactId)
        assertEquals(1, rowsAffected)
    }
}
