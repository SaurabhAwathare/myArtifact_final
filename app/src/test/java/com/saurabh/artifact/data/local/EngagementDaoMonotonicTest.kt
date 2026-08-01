package com.saurabh.artifact.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.saurabh.artifact.model.SyncState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EngagementDaoMonotonicTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: EngagementDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.engagementDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `updateUnlockStatus should succeed even if timestamp is equal if isUnlocked is true`() = runBlocking {
        val artifactId = "art123"
        val timestamp = 1000L
        
        val initial = createEngagement(artifactId, isUnlocked = false, remoteUpdated = timestamp)
        dao.insertEngagement(initial)

        // Try to update with SAME timestamp but isUnlocked = true
        dao.updateUnlockStatus(
            artifactId = artifactId,
            isUnlocked = true,
            timestamp = timestamp + 10,
            state = "UNLOCKED",
            reason = "THRESHOLD",
            remoteUpdated = timestamp // SAME AS INITIAL
        )

        val result = dao.getEngagement(artifactId)
        assertNotNull(result)
        assertTrue("Unlock should be successful despite equal timestamp", result!!.isCommentUnlocked)
        assertEquals(timestamp, result.remoteUpdatedAt)
    }

    @Test
    fun `insertEngagementMonotonic should preserve unlocked state even if incoming is locked`() = runBlocking {
        val artifactId = "art123"
        
        // 1. Authoritative Unlock in DB
        val authoritative = createEngagement(artifactId, isUnlocked = true, remoteUpdated = 2000L)
        dao.insertEngagement(authoritative)

        // 2. Stale evidence from tracker (still thinks it's locked)
        val staleTrackerEvidence = createEngagement(artifactId, isUnlocked = false, remoteUpdated = null)
        
        dao.insertEngagementMonotonic(staleTrackerEvidence)

        val result = dao.getEngagement(artifactId)
        assertNotNull(result)
        assertTrue("Should PRESERVE unlocked state", result!!.isCommentUnlocked)
        assertEquals(2000L, result.remoteUpdatedAt)
    }

    @Test
    fun `insertEngagementMonotonic should update locked state if both are locked`() = runBlocking {
        val artifactId = "art123"
        
        val initial = createEngagement(artifactId, isUnlocked = false, position = 100L)
        dao.insertEngagement(initial)

        val updated = createEngagement(artifactId, isUnlocked = false, position = 500L)
        dao.insertEngagementMonotonic(updated)

        val result = dao.getEngagement(artifactId)
        assertEquals(500L, result?.lastPositionMs)
        assertFalse(result!!.isCommentUnlocked)
    }

    @Test
    fun `isUnlocked = false should NOT regress a true state even with newer timestamp`() = runBlocking {
        // This is a safety check: even if a "remote" document somehow lost its unlocked status
        // (which shouldn't happen), we treat unlock as monotonic.
        val artifactId = "art123"
        
        val unlocked = createEngagement(artifactId, isUnlocked = true, remoteUpdated = 1000L)
        dao.insertEngagement(unlocked)

        // Remote somehow says it's locked again at t=2000
        dao.updateUnlockStatus(
            artifactId = artifactId,
            isUnlocked = false,
            timestamp = 2000L,
            state = "LOCKED",
            reason = null,
            remoteUpdated = 2000L
        )

        val result = dao.getEngagement(artifactId)
        assertTrue("Monotonic unlock: should NOT regress to false", result!!.isCommentUnlocked)
    }

    private fun createEngagement(
        id: String, 
        isUnlocked: Boolean, 
        remoteUpdated: Long? = null,
        position: Long = 0L
    ): ArtifactEngagement {
        return ArtifactEngagement(
            artifactId = id,
            versionTag = "v1",
            durationMs = 10000,
            coverage = byteArrayOf(),
            lastPositionMs = position,
            furthestPositionMs = position,
            hasReachedEnd = false,
            syncState = SyncState.SYNCED,
            isCommentUnlocked = isUnlocked,
            remoteUpdatedAt = remoteUpdated,
            engagementState = if (isUnlocked) "UNLOCKED" else "LOCKED"
        )
    }
}
