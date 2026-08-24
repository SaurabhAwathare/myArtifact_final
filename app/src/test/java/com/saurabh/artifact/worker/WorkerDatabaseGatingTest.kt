package com.saurabh.artifact.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.data.local.DatabaseMaintenanceManager
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.startup.StartupComponent
import com.saurabh.artifact.startup.StartupCoordinator
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import dagger.Lazy

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WorkerDatabaseGatingTest {

    private lateinit var context: Context
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val localDraftManager = mockk<com.saurabh.artifact.audio.LocalDraftManager>(relaxed = true)
    private val maintenanceManager = mockk<DatabaseMaintenanceManager>(relaxed = true)
    private val startupCoordinator = mockk<StartupCoordinator>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private val workerParams = mockk<WorkerParameters>(relaxed = true)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `CleanupOrphanFilesWorker should suspend until DATABASE is ready`() = runTest {
        // 1. Setup: DATABASE is NOT ready
        val dbReady = CompletableDeferred<Unit>()
        coEvery { startupCoordinator.awaitComponent(StartupComponent.DATABASE) } coAnswers { dbReady.await() }

        val worker = CleanupOrphanFilesWorker(
            context,
            workerParams,
            Lazy { draftDao },
            localDraftManager,
            maintenanceManager,
            startupCoordinator,
            diagnosticLogger
        )

        // 2. Act: Start doWork in a separate coroutine
        val job = launch {
            worker.doWork()
        }
        
        // 3. Assert: Worker is suspended and hasn't accessed the DAO
        testScheduler.runCurrent()
        coVerify(exactly = 0) { draftDao.getAllDrafts() }
        
        // 4. Signal DATABASE readiness
        dbReady.complete(Unit)
        testScheduler.runCurrent()

        // 5. Assert: Worker now proceeds
        coVerify(atLeast = 1) { draftDao.getAllDrafts() }
        job.cancel()
    }
}
