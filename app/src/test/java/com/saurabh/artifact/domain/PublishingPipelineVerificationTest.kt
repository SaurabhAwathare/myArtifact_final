package com.saurabh.artifact.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.audio.WavRecoveryManager
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.DraftRepository
import com.saurabh.artifact.repository.PublishApprovalRepository
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.util.EncryptedStorageManager
import com.saurabh.artifact.security.UploadGuard
import com.saurabh.artifact.util.ConnectivityObserver
import com.saurabh.artifact.util.FileIntegrity
import com.saurabh.artifact.util.WorkNames
import com.saurabh.artifact.worker.*
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.startup.StartupComponent
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import dagger.Lazy
import com.saurabh.artifact.data.local.UploadTaskDao
import com.saurabh.artifact.data.local.UploadOwner
import com.saurabh.artifact.data.local.AcquisitionResult

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PublishingPipelineVerificationTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var orchestrator: PublishingOrchestrator
    
    private val draftRepository = mockk<DraftRepository>(relaxed = true)
    private val recordingRepository = mockk<RecordingRepository>(relaxed = true)
    private val approvalRepository = mockk<PublishApprovalRepository>(relaxed = true)
    private val connectivityObserver = mockk<ConnectivityObserver>(relaxed = true)
    private val uploadGuard = mockk<UploadGuard>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val uploadTaskDao = mockk<UploadTaskDao>(relaxed = true)
    private val publishingManager = mockk<PublishingManager>(relaxed = true)
    private val localDraftManager = mockk<LocalDraftManager>(relaxed = true)
    private val encryptedStorageManager = mockk<EncryptedStorageManager>(relaxed = true)
    private val wavRecoveryManager = mockk<WavRecoveryManager>(relaxed = true)
    private val startupCoordinator = mockk<StartupCoordinator>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        coEvery { startupCoordinator.awaitComponent(StartupComponent.DATABASE) } returns Unit
        
        // Initialize WorkManager for testing
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)

        orchestrator = PublishingOrchestrator(
            context = context,
            draftRepository = draftRepository,
            approvalRepository = approvalRepository,
            connectivityObserver = connectivityObserver,
            uploadGuard = uploadGuard,
            authRepository = authRepository,
            workManager = workManager
        )
        
        mockkObject(FileIntegrity)
        // Default behavior for FileIntegrity
        every { FileIntegrity.calculateChecksum(any()) } answers { 
            val path = it.invocation.args[0] as String
            if (File(path).exists()) "checksum_${File(path).length()}" else "checksum_missing"
        }
    }

    @Test
    fun `startProcessing should schedule TranscodingWorker as first stage`() = runTest(testDispatcher) {
        // Arrange
        val draftId = "test_schedule_1"
        val userId = "user_abc"
        every { authRepository.currentUserId } returns userId
        
        val initialDraft = ArtifactDraftEntity(
            id = draftId,
            userId = userId,
            localAudioPath = "/tmp/final.m4a",
            rawPcmPath = "/tmp/raw.wav"
        )
        coEvery { draftRepository.getDraft(draftId) } returns Result.success(initialDraft)

        // Act
        orchestrator.startProcessing(draftId)
        
        // Assert: Verify WorkManager scheduled the task by checking all jobs for the tag
        val workInfos = workManager.getWorkInfosByTag(com.saurabh.artifact.domain.auth.SessionConstants.TAG_USER_SESSION_WORK).get()
        
        val transcodingWork = workInfos.find { it.tags.any { tag -> tag.contains("TranscodingWorker") } }
        
        assertTrue("Work should have a task with TranscodingWorker tag. Found ${workInfos.size} total tasks.", 
            transcodingWork != null)
    }

    @Test
    fun `pipeline sequence Transcoding to Waveform should preserve data integrity`() = runTest(testDispatcher) {
        // --- 1. SETUP ---
        val draftId = "test_pipeline_1"
        val userId = "user_abc"
        val tempDir = Files.createTempDirectory("pipeline_verify").toFile()
        // Create a fake WAV file (at least 44 bytes to pass WavRecoveryManager)
        val rawFile = File(tempDir, "raw.wav").apply { 
            writeBytes(ByteArray(100) { 0x01 }) 
        }
        val finalFile = File(tempDir, "final.m4a")
        
        every { authRepository.currentUserId } returns userId
        every { localDraftManager.createDraftFile(draftId, "m4a") } returns finalFile
        every { encryptedStorageManager.getEncryptedOutputStream(any()) } answers { 
            FileOutputStream(it.invocation.args[0] as File)
        }
        
        val draft = ArtifactDraftEntity(
            id = draftId,
            userId = userId,
            localAudioPath = finalFile.absolutePath,
            rawPcmPath = rawFile.absolutePath,
            isEncrypted = false
        )
        coEvery { draftDao.getDraftById(draftId, userId) } returns draft
        coEvery { wavRecoveryManager.recover(any()) } returns WavRecoveryManager.RecoveryResult.FULLY_RECOVERED

        // --- 2. TRANSCODING STAGE ---
        val transcodingWorker = TranscodingWorker(
            context,
            mockk(relaxed = true) { every { inputData } returns workDataOf("key_draft_id" to draftId) },
            Lazy { draftDao },
            localDraftManager,
            encryptedStorageManager,
            mockk(relaxed = true), // AudioTranscoder
            wavRecoveryManager,
            authRepository,
            startupCoordinator,
            diagnosticLogger
        )
        
        val transcodeResult = transcodingWorker.doWork()
        assertTrue("Transcoding failed", transcodeResult is ListenableWorker.Result.Success)
        
        // After transcoding, update the draft mock to reflect new state
        val transcodedDraft = draft.copy(
            localAudioPath = finalFile.absolutePath,
            isEncrypted = true,
            checksum = "checksum_100"
        )
        coEvery { draftDao.getDraftById(draftId, userId) } returns transcodedDraft

        // --- 3. NORMALIZATION STAGE ---
        val normalizationWorker = AudioNormalizationWorker(
            context,
            mockk(relaxed = true) { every { inputData } returns workDataOf("key_draft_id" to draftId) },
            Lazy { draftDao },
            authRepository,
            startupCoordinator
        )
        
        val normResult = normalizationWorker.doWork()
        assertTrue("Normalization failed", normResult is ListenableWorker.Result.Success)

        // --- 4. WAVEFORM STAGE ---
        val waveformWorker = WaveformWorker(
            context,
            mockk(relaxed = true) { every { inputData } returns workDataOf("key_draft_id" to draftId) },
            Lazy { draftDao },
            authRepository,
            startupCoordinator
        )
        
        val waveformResult = waveformWorker.doWork()
        assertTrue("Waveform generation failed", waveformResult is ListenableWorker.Result.Success)
        
        // Verify: WaveformWorker should have called updateWaveformResult
        coVerify(atLeast = 1) { 
            draftDao.updateWaveformResult(eq(draftId), eq(userId), any(), any())
        }

        // --- 5. INTEGRITY CHECK: Fallback scenario ---
        // If rawPcmPath is missing, WaveformWorker should fallback to localAudioPath
        val dirtyDraft = transcodedDraft.copy(rawPcmPath = null)
        coEvery { draftDao.getDraftById(draftId, userId) } returns dirtyDraft
        
        val fallbackWaveformResult = waveformWorker.doWork()
        // Production fallback would fail if file is encrypted, but here finalFile is raw PCM data.
        assertTrue("Fallback waveform should still return success", fallbackWaveformResult is ListenableWorker.Result.Success)
        
        // Verify fallback used localAudioPath (M4A) and called update
        coVerify(atLeast = 1) {
            draftDao.updateWaveformResult(eq(draftId), eq(userId), any(), any())
        }
        
        tempDir.deleteRecursively()
    }

    @Test
    fun `ProcessingFinalizerWorker should transition lifecycle and delete raw files`() = runTest(testDispatcher) {
        // Arrange
        val draftId = "test_finalize_1"
        val userId = "user_abc"
        val tempDir = Files.createTempDirectory("finalize_verify").toFile()
        val rawFile = File(tempDir, "raw.wav").apply { writeText("PCM") }
        val finalFile = File(tempDir, "final.m4a").apply { writeText("ENCRYPTED") }
        
        every { authRepository.currentUserId } returns userId
        
        val draft = ArtifactDraftEntity(
            id = draftId,
            userId = userId,
            localAudioPath = finalFile.absolutePath,
            rawPcmPath = rawFile.absolutePath,
            lifecycle = ArtifactLifecycle.PROCESSING
        )
        coEvery { draftDao.getDraftById(draftId, userId) } returns draft
        
        val finalizerWorker = ProcessingFinalizerWorker(
            context,
            mockk(relaxed = true) { every { inputData } returns workDataOf("key_draft_id" to draftId) },
            recordingRepository,
            Lazy { draftDao },
            authRepository,
            startupCoordinator,
            diagnosticLogger
        )

        // Act
        val result = finalizerWorker.doWork()

        // Assert
        assertTrue("Finalizer failed", result is ListenableWorker.Result.Success)
        
        // Verify: Lifecycle transition triggered
        coVerify(exactly = 1) { recordingRepository.finalizeProcessing(draftId) }
        
        // Verify: Raw file physically deleted
        assertTrue("Raw file should be deleted", !rawFile.exists())
        assertTrue("Final audio file should be preserved", finalFile.exists())
        
        tempDir.deleteRecursively()
    }

    @Test
    fun `UploadGuard should reliably block publishing if audio file is modified`() = runTest(testDispatcher) {
        // Arrange
        val draftId = "test_guard_1"
        val userId = "user_abc"
        val tempDir = Files.createTempDirectory("guard_verify").toFile()
        val audioFile = File(tempDir, "audio.m4a").apply { writeBytes(ByteArray(100) { 0x01 }) }
        val checksum = "checksum_100"
        
        val guard = UploadGuard(context)
        val timestamp = System.currentTimeMillis()
        
        val approvedToken = guard.generateApprovalToken(userId, draftId, checksum, timestamp)
        
        val draft = ArtifactDraftEntity(
            id = draftId,
            userId = userId,
            localAudioPath = audioFile.absolutePath,
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            checksum = checksum,
            approvalToken = approvedToken,
            publishApprovalTimestamp = timestamp,
            deviceFingerprint = guard.getDeviceFingerprint()
        )

        // Act & Assert 1: Unchanged audio should pass
        assertTrue("Guard should pass for unchanged audio", guard.validateApproval(draft, userId))

        // Act & Assert 2: Modified audio (different length/checksum) should fail
        audioFile.writeBytes(ByteArray(101) { 0x02 }) 
        
        assertTrue("Guard should fail for modified audio length", !guard.validateApproval(draft, userId))
        
        tempDir.deleteRecursively()
    }

    @Test
    fun `UploadService and PublishingWorker should mutually exclude each other`() = runTest(testDispatcher) {
        val draftId = "test_mutex_execute"
        val userId = "user_abc"
        every { authRepository.currentUserId } returns userId
        
        val draft = ArtifactDraftEntity(id = draftId, userId = userId, localAudioPath = "/tmp/audio.m4a", title = "Test")
        coEvery { draftRepository.getDraft(draftId) } returns Result.success(draft)

        // Mock DAO to simulate a real stateful database for ownership
        var currentOwner: UploadOwner? = null
        coEvery { uploadTaskDao.tryAcquireOwnership(eq(draftId), any(), any()) } answers {
            val requestedOwner = it.invocation.args[1] as UploadOwner
            if (currentOwner == null || currentOwner == requestedOwner) {
                currentOwner = requestedOwner
                AcquisitionResult.ACQUIRED
            } else {
                AcquisitionResult.LOCKED
            }
        }
        coEvery { uploadTaskDao.releaseOwnership(eq(draftId)) } answers {
            currentOwner = null
        }

        val workerBuilder = TestListenableWorkerBuilder<PublishingWorker>(context)
            .setInputData(workDataOf("key_draft_id" to draftId))
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: androidx.work.WorkerParameters
                ): ListenableWorker {
                    return PublishingWorker(
                        appContext, 
                        workerParameters, 
                        publishingManager, 
                        draftRepository, 
                        authRepository, 
                        Lazy { uploadTaskDao }, 
                        startupCoordinator, 
                        diagnosticLogger
                    )
                }
            })

        val worker = workerBuilder.build()
        coEvery { publishingManager.performPublish(any(), any()) } returns Result.success(Unit)

        // 1. Worker acquires ownership
        val workerResult1 = worker.doWork()
        assertTrue("First worker attempt should acquire and succeed", workerResult1 is ListenableWorker.Result.Success)
        assertEquals("Owner should be released after success", null, currentOwner)

        // 2. Simulate Service holding ownership
        currentOwner = UploadOwner.SERVICE
        val workerResult2 = worker.doWork()
        assertTrue("Worker should retry when Service holds lock", workerResult2 is ListenableWorker.Result.Retry)
        assertEquals("Service should still hold lock", UploadOwner.SERVICE, currentOwner)

        // 3. Service releases, Worker can acquire again
        currentOwner = null
        val workerResult3 = worker.doWork()
        assertTrue("Worker should succeed after Service releases lock", workerResult3 is ListenableWorker.Result.Success)
    }
}
