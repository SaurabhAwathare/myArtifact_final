package com.saurabh.artifact.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.data.local.DatabaseMaintenanceManager
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.startup.StartupComponent
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.security.DatabaseEncryptionManager
import com.saurabh.artifact.domain.PublishingOrchestrator
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import dagger.Lazy

class CleanupDiscoveryOrderingTest {

    private val context = mockk<Context>(relaxed = true)
    private val workerParams = mockk<WorkerParameters>(relaxed = true)
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val localDraftManager = mockk<LocalDraftManager>(relaxed = true)
    private val maintenanceManager = mockk<DatabaseMaintenanceManager>(relaxed = true)
    private val startupCoordinator = mockk<StartupCoordinator>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    
    private val recordingRepository = mockk<RecordingRepository>(relaxed = true)
    private val encryptionManager = mockk<DatabaseEncryptionManager>(relaxed = true)
    private val publishingOrchestrator = mockk<PublishingOrchestrator>(relaxed = true)

    private val componentDeferreds = mutableMapOf<StartupComponent, CompletableDeferred<Unit>>()

    private fun getDeferred(component: StartupComponent) = componentDeferreds.getOrPut(component) { CompletableDeferred() }

    @Before
    fun setup() {
        componentDeferreds.clear()
        
        coEvery { startupCoordinator.awaitComponent(any()) } coAnswers {
            val component = it.invocation.args[0] as StartupComponent
            getDeferred(component).await()
        }
        
        every { startupCoordinator.emitReadiness(any()) } answers {
            val component = it.invocation.args[0] as StartupComponent
            getDeferred(component).complete(Unit)
        }
    }

    @Test
    fun `CleanupOrphanFilesWorker should wait for FILESYSTEM_DISCOVERY from RecoveryWorker`() = runTest {
        val cleanupWorker = CleanupOrphanFilesWorker(
            context, workerParams, Lazy { draftDao }, localDraftManager, 
            maintenanceManager, startupCoordinator, diagnosticLogger
        )
        
        val recoveryWorker = RecoveryWorker(
            context, workerParams, recordingRepository, encryptionManager,
            publishingOrchestrator, startupCoordinator, diagnosticLogger
        )

        // 1. Start CleanupWorker in a separate coroutine
        val cleanupJob = launch {
            cleanupWorker.doWork()
        }

        // 2. Verify cleanup hasn't proceeded (it's waiting for DATABASE and DISCOVERY)
        yield()
        verify(exactly = 0) { localDraftManager.reconcileStorage(any()) }

        // 3. Signal DATABASE readiness
        getDeferred(StartupComponent.DATABASE).complete(Unit)
        yield()
        verify(exactly = 0) { localDraftManager.reconcileStorage(any()) }

        // 4. Run RecoveryWorker (which signals DISCOVERY)
        recoveryWorker.doWork()
        
        // 5. Now CleanupWorker should finish
        cleanupJob.join()
        
        // 6. Verify reconciliation happened AFTER discovery
        verify(exactly = 1) { localDraftManager.reconcileStorage(any()) }
    }

    @Test
    fun `CleanupOrphanFilesWorker should retry if discovery times out`() = runTest {
        val cleanupWorker = CleanupOrphanFilesWorker(
            context, workerParams, Lazy { draftDao }, localDraftManager, 
            maintenanceManager, startupCoordinator, diagnosticLogger
        )

        // 1. Signal DATABASE readiness
        getDeferred(StartupComponent.DATABASE).complete(Unit)
        
        // 2. Do NOT signal FILESYSTEM_DISCOVERY and let it time out
        // CleanupOrphanFilesWorker uses withTimeout(30.seconds).
        // runTest will fast-forward this.
        
        val result = cleanupWorker.doWork()
        
        assertEquals(ListenableWorker.Result.retry(), result)
        verify(exactly = 0) { localDraftManager.reconcileStorage(any()) }
    }
}
