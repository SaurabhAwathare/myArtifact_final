package com.saurabh.artifact.worker

import android.content.Context
import androidx.work.*
import com.saurabh.artifact.domain.auth.SessionConstants
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class InteractionSyncWorkerTagTest {

    private val context = mockk<Context>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)

    @Before
    fun setup() {
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `InteractionSyncWorker_enqueue should add TAG_USER_SESSION_WORK to the work request`() {
        val requestSlot = slot<OneTimeWorkRequest>()
        
        every { 
            workManager.enqueueUniqueWork(
                InteractionSyncWorker.TAG,
                ExistingWorkPolicy.KEEP,
                capture(requestSlot)
            )
        } returns mockk()

        InteractionSyncWorker.enqueue(context)

        val capturedRequest = requestSlot.captured
        assert(capturedRequest.tags.contains(SessionConstants.TAG_USER_SESSION_WORK))
        assert(capturedRequest.tags.contains(InteractionSyncWorker.TAG))
    }

    @Test
    fun `EngagementSyncScheduler_scheduleSync should result in a session-tagged work request`() {
        val scheduler = EngagementSyncScheduler(context)
        val requestSlot = slot<OneTimeWorkRequest>()

        every { 
            workManager.enqueueUniqueWork(
                InteractionSyncWorker.TAG,
                ExistingWorkPolicy.KEEP,
                capture(requestSlot)
            )
        } returns mockk()

        scheduler.scheduleSync()

        val capturedRequest = requestSlot.captured
        assert(capturedRequest.tags.contains(SessionConstants.TAG_USER_SESSION_WORK))
        assert(capturedRequest.tags.contains(InteractionSyncWorker.TAG))
    }
}
