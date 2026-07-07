package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.domain.PublishingOrchestrator
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.security.DatabaseEncryptionManager
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.Result as KResult

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryWorkerTest {
    private val recordingRepository = mockk<RecordingRepository>(relaxed = true)
    private val encryptionManager = mockk<DatabaseEncryptionManager>(relaxed = true)
    private val publishingOrchestrator = mockk<PublishingOrchestrator>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val workerParams = mockk<WorkerParameters>(relaxed = true)

    private lateinit var worker: RecoveryWorker

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        worker = RecoveryWorker(
            appContext = context,
            workerParams = workerParams,
            recordingRepository = recordingRepository,
            encryptionManager = encryptionManager,
            publishingOrchestrator = publishingOrchestrator,
            diagnosticLogger = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `doWork should trigger recovery for identified drafts`() = runTest {
        val draftId = "draft_123"
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
        }
        
        coEvery { recordingRepository.recoverInterruptedDrafts() } returns KResult.success(listOf(draft))
        coEvery { publishingOrchestrator.isProcessingActive(draftId) } returns false

        val result = worker.doWork()

        assert(result is ListenableWorker.Result.Success)
        coVerify(exactly = 1) { recordingRepository.markRecoveryAttempt(draftId) }
        coVerify(exactly = 1) { publishingOrchestrator.startProcessing(draftId) }
    }

    @Test
    fun `doWork should not restart active processing`() = runTest {
        val draftId = "draft_123"
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
        }
        
        coEvery { recordingRepository.recoverInterruptedDrafts() } returns KResult.success(listOf(draft))
        coEvery { publishingOrchestrator.isProcessingActive(draftId) } returns true

        val result = worker.doWork()

        assert(result is ListenableWorker.Result.Success)
        coVerify(exactly = 0) { recordingRepository.markRecoveryAttempt(any()) }
        coVerify(exactly = 0) { publishingOrchestrator.startProcessing(any()) }
    }

    @Test
    fun `doWork should handle multiple drafts independently`() = runTest {
        val draft1 = mockk<ArtifactDraftEntity>(relaxed = true) { every { id } returns "d1" }
        val draft2 = mockk<ArtifactDraftEntity>(relaxed = true) { every { id } returns "d2" }
        
        coEvery { recordingRepository.recoverInterruptedDrafts() } returns KResult.success(listOf(draft1, draft2))
        coEvery { publishingOrchestrator.isProcessingActive("d1") } returns false
        coEvery { publishingOrchestrator.isProcessingActive("d2") } returns true

        worker.doWork()

        coVerify(exactly = 1) { publishingOrchestrator.startProcessing("d1") }
        coVerify(exactly = 0) { publishingOrchestrator.startProcessing("d2") }
    }
}
