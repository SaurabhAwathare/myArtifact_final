package com.saurabh.artifact.data.local

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class DatabaseMaintenanceManagerTest {

    private val database = mockk<AppDatabase>()
    private val engagementDao = mockk<EngagementDao>(relaxed = true)
    private val interactionDao = mockk<PendingInteractionDao>(relaxed = true)
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val authRepository = mockk<com.saurabh.artifact.repository.AuthRepository>(relaxed = true)
    private val openHelper = mockk<SupportSQLiteOpenHelper>()
    private val writableDb = mockk<SupportSQLiteDatabase>(relaxed = true)

    private lateinit var manager: DatabaseMaintenanceManager

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        every { database.openHelper } returns openHelper
        every { openHelper.writableDatabase } returns writableDb
        every { authRepository.currentUserId } returns "test_user"
        
        manager = DatabaseMaintenanceManager(
            { database },
            { engagementDao },
            { interactionDao },
            { draftDao },
            authRepository
        )
    }

    @Test
    fun `runMaintenance calls all prune methods with correct timestamps and userId`() = runBlocking {
        val now = System.currentTimeMillis()
        val userId = "test_user"
        
        manager.runMaintenance()

        // Verify Engagement pruning (60 days)
        val engagementThreshold = slot<Long>()
        coVerify { engagementDao.deleteOldEngagements(capture(engagementThreshold), userId) }
        assertWithinRange(engagementThreshold.captured, now - TimeUnit.DAYS.toMillis(60))

        // Verify Interaction pruning (30 days)
        val interactionThreshold = slot<Long>()
        coVerify { interactionDao.deleteOldInteractions(capture(interactionThreshold), userId) }
        assertWithinRange(interactionThreshold.captured, now - TimeUnit.DAYS.toMillis(30))

        // Verify Draft pruning (30 days)
        val draftThreshold = slot<Long>()
        coVerify { draftDao.deleteOldPublishedDrafts(capture(draftThreshold), userId) }
        assertWithinRange(draftThreshold.captured, now - TimeUnit.DAYS.toMillis(30))
    }

    @Test
    fun `runMaintenance executes VACUUM`() = runBlocking {
        manager.runMaintenance()
        
        verify { writableDb.execSQL("VACUUM") }
    }

    private fun assertWithinRange(actual: Long, expected: Long, toleranceMs: Long = 5000) {
        val diff = Math.abs(actual - expected)
        if (diff > toleranceMs) {
            throw AssertionError("Timestamp $actual is not within $toleranceMs ms of expected $expected (diff: $diff)")
        }
    }
}
