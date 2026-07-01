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
    private val uploadDao = mockk<QueuedUploadDao>(relaxed = true)
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
        
        manager = DatabaseMaintenanceManager(
            { database },
            { engagementDao },
            { interactionDao },
            { draftDao },
            { uploadDao }
        )
    }

    @Test
    fun `runMaintenance calls all prune methods with correct timestamps`() = runBlocking {
        val now = System.currentTimeMillis()
        
        manager.runMaintenance()

        // Verify Engagement pruning (60 days)
        val engagementThreshold = slot<Long>()
        coVerify { engagementDao.deleteOldEngagements(capture(engagementThreshold)) }
        assertWithinRange(engagementThreshold.captured, now - TimeUnit.DAYS.toMillis(60))

        // Verify Interaction pruning (30 days)
        val interactionThreshold = slot<Long>()
        coVerify { interactionDao.deleteOldInteractions(capture(interactionThreshold)) }
        assertWithinRange(interactionThreshold.captured, now - TimeUnit.DAYS.toMillis(30))

        // Verify Draft pruning (30 days)
        val draftThreshold = slot<Long>()
        coVerify { draftDao.deleteOldPublishedDrafts(capture(draftThreshold)) }
        assertWithinRange(draftThreshold.captured, now - TimeUnit.DAYS.toMillis(30))

        // Verify Upload pruning (7 days)
        val uploadThreshold = slot<Long>()
        coVerify { uploadDao.deleteOldQueuedUploads(capture(uploadThreshold)) }
        assertWithinRange(uploadThreshold.captured, now - TimeUnit.DAYS.toMillis(7))
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
