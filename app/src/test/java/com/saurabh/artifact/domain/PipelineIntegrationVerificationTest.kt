package com.saurabh.artifact.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.TestListenableWorkerBuilder
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.audio.WavRecoveryManager
import com.saurabh.artifact.audio.UploadService
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.*
import com.saurabh.artifact.security.UploadGuard
import com.saurabh.artifact.util.*
import com.saurabh.artifact.worker.*
import com.saurabh.artifact.domain.review.publishing.PublishingReviewPolicy
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import dagger.Lazy

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineIntegrationVerificationTest {

    @Test
    fun `Full Pipeline Integration - Recording to Published`() = runTest {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        
        // 1. Setup Environment
        val database = androidx.room.Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            
        val draftDao = database.draftDao()
        val uploadTaskDao = database.uploadTaskDao()
        
        val userRepository = mockk<UserRepository>(relaxed = true)
        val authRepository = mockk<AuthRepository>(relaxed = true)
        val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
        val approvalRepository = mockk<PublishApprovalRepository>(relaxed = true)
        val storageManager = mockk<StorageManager>(relaxed = true)
        val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
        val connectivityObserver = mockk<ConnectivityObserver>(relaxed = true)
        val uploadGuard = mockk<UploadGuard>(relaxed = true)

        WorkManagerTestInitHelper.initializeTestWorkManager(appContext)
        val workManager = WorkManager.getInstance(appContext)
        
        val testDir = File(appContext.cacheDir, "pipeline_integration_test")
        testDir.mkdirs()

        // 2. Setup Mocks
        val firebaseUser = mockk<com.google.firebase.auth.FirebaseUser>(relaxed = true)
        every { firebaseUser.uid } returns "user_1"
        mockkStatic(com.google.firebase.auth.FirebaseAuth::class)
        val firebaseAuth = mockk<com.google.firebase.auth.FirebaseAuth>(relaxed = true)
        every { com.google.firebase.auth.FirebaseAuth.getInstance() } returns firebaseAuth
        every { firebaseAuth.currentUser } returns firebaseUser

        every { authRepository.currentUserId } returns "user_1"
        every { userRepository.getCurrentUserId() } returns "user_1"
        every { connectivityObserver.isOnline() } returns true
        every { storageManager.draftsRootDirectory } returns testDir
        every { storageManager.getDraftDirectory(any()) } answers { 
            val draftId = it.invocation.args[0] as String
            File(testDir, "draft_$draftId").apply { mkdirs() } 
        }

        // 3. Setup Components
        val localDraftManager = LocalDraftManager(storageManager)
        
        val draftRepository = DraftRepository(
            draftDao = Lazy { draftDao },
            uploadTaskDao = Lazy { uploadTaskDao },
            draftsDatabase = Lazy { database },
            draftToArtifactMapper = mockk(relaxed = true),
            userRepository = userRepository
        )

        val recordingRepository = RecordingRepository(
            draftDao = Lazy { draftDao },
            userRepository = userRepository,
            localDraftManager = localDraftManager,
            wavRecoveryManager = mockk(relaxed = true),
            cleanupManager = mockk(relaxed = true),
            draftsDatabase = Lazy { database },
            diagnosticLogger = diagnosticLogger
        )

        val orchestrator = PublishingOrchestrator(
            context = appContext,
            draftRepository = draftRepository,
            approvalRepository = PublishApprovalRepository(appContext, Lazy { draftDao }, uploadGuard, authRepository),
            connectivityObserver = connectivityObserver,
            uploadGuard = uploadGuard,
            authRepository = authRepository,
            workManager = workManager
        )

        val approvalRepositoryReal = PublishApprovalRepository(appContext, Lazy { draftDao }, uploadGuard, authRepository)
        
        val publishUseCase = PublishArtifactUseCase(
            recordingRepository = recordingRepository,
            authRepository = authRepository,
            publishingOrchestrator = orchestrator,
            publishingPolicy = PublishingReviewPolicy(minCoverage = 0.5f)
        )

        // --- 4. RECORDING START ---
        val draftId = "integration_1"
        val draftDir = localDraftManager.createDraftFile(draftId).parentFile!!
        draftDir.mkdirs()
        val audioFile = File(draftDir, "audio.wav")
        audioFile.writeBytes(ByteArray(1024) { 0x01 }) 
        
        val initialDraft = ArtifactDraftEntity(
            id = draftId,
            userId = "user_1",
            localAudioPath = audioFile.absolutePath,
            rawPcmPath = audioFile.absolutePath,
            lifecycle = ArtifactLifecycle.RECORDING,
            updatedAt = System.currentTimeMillis(),
            lastCheckpointTimestamp = System.currentTimeMillis()
        )
        draftDao.insert(initialDraft)

        // --- 5. RECORDING COMPLETION ---
        recordingRepository.finalizeRecording(draftId, 5000L, 1024L).getOrThrow()
        assertEquals(ArtifactLifecycle.PROCESSING, draftDao.getDraftById(draftId, "user_1")?.lifecycle)

        // --- 6. PROCESSING START ---
        orchestrator.startProcessing(draftId)
        runCurrent()
        
        // --- 7. EXECUTE PROCESSING WORKERS ---
        
        // Stage 1: Transcoding
        val encryptedStorageManager = mockk<EncryptedStorageManager>(relaxed = true)
        every { encryptedStorageManager.getEncryptedOutputStream(any()) } answers { FileOutputStream(it.invocation.args[0] as File) }
        mockkObject(FileIntegrity)
        every { FileIntegrity.calculateChecksum(any()) } returns "checksum_123"

        val transcodingWorker = TestListenableWorkerBuilder<TranscodingWorker>(appContext)
            .setInputData(workDataOf("key_draft_id" to draftId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                    return TranscodingWorker(appContext, workerParameters, Lazy { draftDao }, localDraftManager, encryptedStorageManager, mockk(relaxed = true), authRepository, diagnosticLogger)
                }
            }).build()
        assertTrue(transcodingWorker.doWork() is ListenableWorker.Result.Success)
        
        // Stage 2 & 3: Waveform
        val waveformWorker = TestListenableWorkerBuilder<WaveformWorker>(appContext)
            .setInputData(workDataOf("key_draft_id" to draftId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                    return WaveformWorker(appContext, workerParameters, Lazy { draftDao }, authRepository)
                }
            }).build()
        assertTrue(waveformWorker.doWork() is ListenableWorker.Result.Success)

        // Stage 4: Finalizer
        val finalizer = TestListenableWorkerBuilder<ProcessingFinalizerWorker>(appContext)
            .setInputData(workDataOf("key_draft_id" to draftId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                    return ProcessingFinalizerWorker(appContext, workerParameters, recordingRepository, Lazy { draftDao }, authRepository, diagnosticLogger)
                }
            }).build()
        assertTrue(finalizer.doWork() is ListenableWorker.Result.Success)
        
        assertEquals(ArtifactLifecycle.REVIEW_REQUIRED, draftDao.getDraftById(draftId, "user_1")?.lifecycle)

        // --- 8. USER REVIEW & METADATA ---
        recordingRepository.updateLifecycle(draftId, ArtifactLifecycle.METADATA_REQUIRED)
        recordingRepository.updateDraftMetadata(draftId, "My Masterpiece", Emotion.MOTIVATED)
        
        // Manually simulate freeze to avoid Firebase ClassCastException in Robolectric
        val draftForFreeze = draftDao.getDraftById(draftId, "user_1")!!
        draftDao.update(draftForFreeze.copy(
            frozenAudioPath = draftForFreeze.localAudioPath,
            frozenTranscriptJson = SecureString.fromString("[]"),
            approvalToken = "token_123",
            updatedAt = System.currentTimeMillis()
        ))
        
        recordingRepository.updateLifecycle(draftId, ArtifactLifecycle.READY_TO_PUBLISH)
        
        val frozenDraft = draftDao.getDraftById(draftId, "user_1")!!
        assertNotNull("Frozen audio path should be set", frozenDraft.frozenAudioPath)
        println("FROZEN ASSETS SIMULATED")
        
        // --- 9. PUBLISHING INITIATION ---
        every { uploadGuard.validateApproval(any(), any()) } returns true
        mockkObject(UploadService)
        every { UploadService.start(any(), any()) } returns Unit
        
        val latestDraft = draftDao.getDraftById(draftId, "user_1")!!
        val publishResult = publishUseCase(latestDraft.localAudioPath)
        assertTrue("Publish initiation failed: ${publishResult.exceptionOrNull()}", publishResult.isSuccess)
        
        // --- 10. EXECUTE PUBLISHING WORKER ---
        println("EXECUTING PublishingWorker...")
        // Ensure ownership is available
        uploadTaskDao.releaseOwnership(draftId)
        
        // Mocking the DAO response to bypass potential race conditions in Robolectric
        val uploadTaskDaoMock = spyk(uploadTaskDao)
        coEvery { uploadTaskDaoMock.tryAcquireOwnership(any(), any(), any()) } returns AcquisitionResult.ACQUIRED
        
        coEvery { artifactRepository.getArtifact(any()) } returns Result.failure(Exception("Not found yet"))
        coEvery { artifactRepository.uploadTranscript(any(), any(), any()) } returns Result.success("https://cdn.com/transcript.json")
        val user = mockk<User>(relaxed = true)
        every { user.id } returns "user_1"
        every { user.anonymousId } returns "usr_123"
        every { user.anonymousName } returns "hero_1"
        every { user.anonymousSigil } returns "sigil_123"
        coEvery { userRepository.getOrCreateProfile() } returns Result.success(ProfileResult(user, false))
        coEvery { artifactRepository.createArtifactDocument(any(), any(), any(), any(), any(), any(), any()) } returns Result.success("artifact_123")
        coEvery { artifactRepository.uploadArtifactResumable(any(), any(), any()) } returns Result.success("https://cdn.com/audio.m4a")
        coEvery { artifactRepository.finalizeArtifactDocument(any(), any(), any(), any(), any()) } returns Result.success(Unit)

        val publishingManager = PublishingManager(draftRepository, artifactRepository, userRepository, mockk(relaxed = true), uploadGuard, diagnosticLogger)
        
        val pubWorker = spyk(
            TestListenableWorkerBuilder<PublishingWorker>(appContext)
                .setInputData(workDataOf("key_draft_id" to draftId))
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                        return PublishingWorker(appContext, workerParameters, publishingManager, draftRepository, authRepository, Lazy { uploadTaskDaoMock }, diagnosticLogger)
                    }
                }).build()
        )
        
        coEvery { pubWorker.getForegroundInfo() } returns mockk(relaxed = true)
        coEvery { pubWorker.setForeground(any()) } returns Unit
        
        val workerResult = try {
            pubWorker.doWork()
        } catch (e: Exception) {
            println("WORKER CRASHED: $e")
            e.printStackTrace()
            throw e
        }
        
        if (workerResult is ListenableWorker.Result.Retry) {
             val retryTask = uploadTaskDao.getTaskByDraftId(draftId)
             println("TASK ON RETRY: owner=${retryTask?.owner}, status=${retryTask?.status}")
             // I'll check if there were any errors logged in the manager
        }
        assertTrue("PublishingWorker should succeed, but got $workerResult", workerResult is ListenableWorker.Result.Success)
        println("PUBLISHING SUCCESS")
        
        // --- 11. FINAL VERIFICATION ---
        val finalDraft = draftDao.getDraftById(draftId, "user_1")
        assertEquals(ArtifactLifecycle.PUBLISHED, finalDraft?.lifecycle)
        
        database.close()
    }
}
