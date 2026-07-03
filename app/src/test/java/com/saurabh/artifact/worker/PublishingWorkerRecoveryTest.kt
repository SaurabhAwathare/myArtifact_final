package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.saurabh.artifact.domain.PublishingOrchestrator
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.repository.RecordingRepository
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.Result as KResult

@OptIn(ExperimentalCoroutinesApi::class)
class PublishingWorkerRecoveryTest {
    private val recordingRepository = mockk<RecordingRepository>(relaxed = true)
    private val publishingOrchestrator = mockk<PublishingOrchestrator>(relaxed = true)
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val workerParams = mockk<WorkerParameters>(relaxed = true)

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `recovery should result in orchestrator starting processing`() = runTest {
        val draftId = "orphaned_123"
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
            every { lifecycle } returns ArtifactLifecycle.PROCESSING
        }

        // 1. Simulate RecoveryWorker finding the draft
        coEvery { recordingRepository.recoverInterruptedDrafts() } returns KResult.success(listOf(draft))
        coEvery { publishingOrchestrator.isProcessingActive(draftId) } returns false

        val worker = RecoveryWorker(
            appContext = context,
            workerParams = workerParams,
            recordingRepository = recordingRepository,
            encryptionManager = mockk(relaxed = true),
            publishingOrchestrator = publishingOrchestrator
        )

        val result = worker.doWork()

        // 2. Verify recovery was triggered
        assert(result is ListenableWorker.Result.Success)
        coVerify { recordingRepository.markRecoveryAttempt(draftId) }
        coVerify { publishingOrchestrator.startProcessing(draftId) }
    }
}
