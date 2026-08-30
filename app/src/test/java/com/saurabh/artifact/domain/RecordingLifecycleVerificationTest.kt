package com.saurabh.artifact.domain

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.audio.RecordingService
import com.saurabh.artifact.audio.WavRecoveryManager
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.UploadTaskDao
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.SyncStatus
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.util.StorageManager
import com.saurabh.artifact.util.WorkNames
import com.saurabh.artifact.data.local.AppDatabase
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import java.io.File
import java.nio.file.Files
import dagger.Lazy
import androidx.room.withTransaction

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingLifecycleVerificationTest {

    private lateinit var context: Context
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val uploadTaskDao = mockk<UploadTaskDao>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val storageManager = mockk<StorageManager>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val wavRecoveryManager = mockk<WavRecoveryManager>(relaxed = true)
    private val userSessionManager = mockk<UserSessionManager>(relaxed = true)
    
    private lateinit var localDraftManager: LocalDraftManager
    private lateinit var cleanupManager: ArtifactCleanupManager
    private lateinit var recordingRepository: RecordingRepository
    
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testDraftsDir: File

    private val authRepository = mockk<com.saurabh.artifact.repository.AuthRepository>(relaxed = true)
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        testDraftsDir = Files.createTempDirectory("lifecycle_test").toFile()
        
        every { storageManager.draftsRootDirectory } returns testDraftsDir
        every { storageManager.getDraftDirectory(any()) } answers { 
            File(testDraftsDir, "draft_${it.invocation.args[0]}").apply { mkdirs() } 
        }
        every { storageManager.isStorageAvailable() } returns true
        
        localDraftManager = LocalDraftManager(storageManager)
        
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        cleanupManager = ArtifactCleanupManager(
            mockk(relaxed = true), // artifactRepository
            authRepository,
            Lazy { draftDao },
            Lazy { uploadTaskDao },
            workManager
        )

        recordingRepository = RecordingRepository(
            draftDao = Lazy { draftDao },
            userRepository = userRepository,
            localDraftManager = localDraftManager,
            wavRecoveryManager = wavRecoveryManager,
            cleanupManager = cleanupManager,
            userSessionManager = userSessionManager,
            draftsDatabase = Lazy { database },
            diagnosticLogger = diagnosticLogger
        )

        mockkStatic("androidx.room.RoomDatabaseKt")
        val transactionCaptor = slot<suspend () -> Any>()
        coEvery { database.withTransaction(capture(transactionCaptor)) } coAnswers {
            transactionCaptor.captured.invoke()
        }
        
        every { userRepository.getCurrentUserId() } returns "user_1"
        every { authRepository.currentUserId } returns "user_1"
        every { userSessionManager.activeDraftId } returns flowOf(null)
    }

    @Test
    fun `Successful completion should transition RECORDING to PROCESSING`() = runTest(testDispatcher) {
        // Arrange
        val draftId = "happy_path"
        val audioFile = File(testDraftsDir, "draft_$draftId/audio.wav").apply { 
            parentFile?.mkdirs()
            writeBytes(ByteArray(100) { 0x01 }) 
        }
        
        val draft = ArtifactDraftEntity(
            id = draftId,
            userId = "user_1",
            localAudioPath = audioFile.absolutePath,
            lifecycle = ArtifactLifecycle.RECORDING
        )
        
        coEvery { draftDao.getDraftById(draftId, "user_1") } returns draft

        // Act
        recordingRepository.finalizeRecording(draftId, 1000L, 100L)

        // Assert
        coVerify { 
            draftDao.update(match { 
                it.id == draftId && it.lifecycle == ArtifactLifecycle.PROCESSING && it.durationMs == 1000L
            }) 
        }
    }

    @Test
    fun `Interrupted stale recording should be recovered to PROCESSING`() = runTest(testDispatcher) {
        // Arrange
        val draftId = "interrupted_stale"
        val audioFile = File(testDraftsDir, "draft_$draftId/audio.wav").apply { 
            parentFile.mkdirs()
            writeBytes(ByteArray(1000) { 0x01 }) 
        }
        
        val staleDraft = ArtifactDraftEntity(
            id = draftId,
            userId = "user_1",
            localAudioPath = audioFile.absolutePath,
            lifecycle = ArtifactLifecycle.RECORDING,
            lastCheckpointTimestamp = System.currentTimeMillis() - 70_000, // 70s old (> 10s)
            durableBytes = 500L
        )
        
        coEvery { draftDao.getActiveRecordings("user_1") } returns listOf(staleDraft)
        coEvery { draftDao.getDraftsByLifecycle(any(), any()) } returns emptyList()
        coEvery { draftDao.getAllDrafts() } returns listOf(staleDraft)
        
        every { wavRecoveryManager.recover(any(), any()) } returns WavRecoveryManager.RecoveryResult.REPAIRED

        // Act
        recordingRepository.recoverInterruptedDrafts()

        // Assert
        coVerify { 
            draftDao.update(match { 
                it.id == draftId && it.lifecycle == ArtifactLifecycle.PROCESSING 
            }, isRecovery = true) 
        }
    }

    @Test
    fun `Active recent recording should be preserved in RECORDING state`() = runTest(testDispatcher) {
        // Arrange
        val draftId = "interrupted_recent"
        val recentDraft = ArtifactDraftEntity(
            id = draftId,
            userId = "user_1",
            lifecycle = ArtifactLifecycle.RECORDING,
            localAudioPath = "/tmp/audio.wav",
            lastCheckpointTimestamp = System.currentTimeMillis() - 5_000 // 5s old (< 10s)
        )
        
        coEvery { draftDao.getActiveRecordings("user_1") } returns listOf(recentDraft)
        coEvery { draftDao.getDraftsByLifecycle(any(), any()) } returns emptyList()
        coEvery { draftDao.getAllDrafts() } returns listOf(recentDraft)

        // Act
        recordingRepository.recoverInterruptedDrafts()

        // Assert
        coVerify(exactly = 0) { draftDao.update(any(), isRecovery = true) }
    }

    @Test
    fun `Stale recording just over 10s should be recovered`() = runTest(testDispatcher) {
        // Arrange
        val draftId = "just_stale"
        val audioFile = File(testDraftsDir, "draft_$draftId/audio.wav").apply { 
            parentFile.mkdirs()
            writeBytes(ByteArray(1000) { 0x01 }) 
        }
        val staleDraft = ArtifactDraftEntity(
            id = draftId,
            userId = "user_1",
            localAudioPath = audioFile.absolutePath,
            lifecycle = ArtifactLifecycle.RECORDING,
            lastCheckpointTimestamp = System.currentTimeMillis() - 11_000, // 11s old (> 10s)
            durableBytes = 500L
        )
        
        coEvery { draftDao.getActiveRecordings("user_1") } returns listOf(staleDraft)
        coEvery { draftDao.getDraftsByLifecycle(any(), any()) } returns emptyList()
        coEvery { draftDao.getAllDrafts() } returns listOf(staleDraft)
        
        every { wavRecoveryManager.recover(any(), any()) } returns WavRecoveryManager.RecoveryResult.REPAIRED

        // Act
        recordingRepository.recoverInterruptedDrafts()

        // Assert
        coVerify { 
            draftDao.update(match { 
                it.id == draftId && it.lifecycle == ArtifactLifecycle.PROCESSING 
            }, isRecovery = true) 
        }
    }

    @Test
    fun `Zombie 0-duration draft should be purged after 30 minutes`() = runTest(testDispatcher) {
        // Arrange
        val draftId = "zombie_to_purge"
        val zombieDraft = ArtifactDraftEntity(
            id = draftId,
            userId = "user_1",
            lifecycle = ArtifactLifecycle.RECORDING,
            localAudioPath = "/tmp/audio.wav",
            durationMs = 0L,
            durableBytes = 0L,
            updatedAt = System.currentTimeMillis() - (40 * 60 * 1000) // 40 mins old (> 30m)
        )
        
        coEvery { draftDao.getActiveRecordings("user_1") } returns listOf(zombieDraft)
        coEvery { draftDao.getDraftsByLifecycle(any(), any()) } returns emptyList()
        coEvery { draftDao.getAllDrafts() } returns listOf(zombieDraft)
        coEvery { draftDao.getDraftById(draftId, "user_1") } returns zombieDraft

        // Act
        recordingRepository.recoverInterruptedDrafts()
        runCurrent()

        // Assert
        val workInfos = WorkManager.getInstance(context).getWorkInfosByTag("cleanup_$draftId").get()
        assertFalse("Cleanup worker should be scheduled for zombie", workInfos.isEmpty())
    }

    @Test
    fun `Empty interrupted recording with NO data should be correctly identified as DELETED`() = runTest(testDispatcher) {
        // Arrange
        val draftId = "empty_interrupted"
        val emptyDraft = ArtifactDraftEntity(
            id = draftId,
            userId = "user_1",
            lifecycle = ArtifactLifecycle.RECORDING,
            localAudioPath = File(testDraftsDir, "draft_$draftId/audio.wav").absolutePath,
            durationMs = 0L,
            durableBytes = 0L,
            lastCheckpointTimestamp = System.currentTimeMillis() - 70_000, // Stale
            updatedAt = System.currentTimeMillis() - (10 * 60 * 1000) // Not yet zombie
        )
        
        coEvery { draftDao.getActiveRecordings("user_1") } returns listOf(emptyDraft)
        coEvery { draftDao.getDraftsByLifecycle(any(), any()) } returns emptyList()
        coEvery { draftDao.getAllDrafts() } returns listOf(emptyDraft)
        coEvery { draftDao.getDraftById(draftId, "user_1") } returns emptyDraft
        
        // Mock recovery finding NO file
        every { wavRecoveryManager.recover(any(), any()) } returns WavRecoveryManager.RecoveryResult.NOT_FOUND

        // Act
        recordingRepository.recoverInterruptedDrafts()

        // Assert: 
        // Recovery moves it to DELETED (because NOT_FOUND)
        coVerify { 
            draftDao.update(match { 
                it.id == draftId && it.lifecycle == ArtifactLifecycle.DELETED 
            }, isRecovery = true) 
        }
    }

    @Test
    fun `Interrupted AAC recording should skip WAV repair and use metadata retriever`() = runTest(testDispatcher) {
        // Arrange
        val draftId = "aac_interrupted"
        val audioFile = File(testDraftsDir, "draft_$draftId/audio.m4a").apply { 
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0, 0, 0, 0, 0x66, 0x74, 0x79, 0x70)) // ftyp magic
        }
        
        val aacDraft = ArtifactDraftEntity(
            id = draftId,
            userId = "user_1",
            localAudioPath = audioFile.absolutePath,
            lifecycle = ArtifactLifecycle.RECORDING,
            mimeType = "audio/mp4",
            lastCheckpointTimestamp = System.currentTimeMillis() - 70_000,
            durableBytes = 8L
        )
        
        coEvery { draftDao.getActiveRecordings("user_1") } returns listOf(aacDraft)
        coEvery { draftDao.getDraftsByLifecycle(any(), any()) } returns emptyList()
        coEvery { draftDao.getAllDrafts() } returns listOf(aacDraft)
        
        // Act
        recordingRepository.recoverInterruptedDrafts()

        // Assert:
        // 1. WAV repair was NOT called
        verify(exactly = 0) { wavRecoveryManager.recover(any(), any()) }
        
        // 2. Draft should be DELETED because we don't have a real duration mock for Retriever
        // In a real device/Robolectric environment with shadows, it might be PROCESSING if duration > 0.
        // Without full mocking, MediaMetadataRetriever will return nulls.
        coVerify { 
            draftDao.update(match { 
                it.id == draftId && it.lifecycle == ArtifactLifecycle.DELETED 
            }, isRecovery = true) 
        }
    }

    @Test
    fun `Active WAV draft should be skipped by recovery regardless of timestamp`() = runTest(testDispatcher) {
        // Arrange
        val draftId = "active_wav"
        val audioFile = File(testDraftsDir, "draft_$draftId/audio.wav").apply { 
            parentFile.mkdirs()
            writeBytes(ByteArray(1000) { 0x01 }) 
        }
        val activeDraft = ArtifactDraftEntity(
            id = draftId,
            userId = "user_1",
            localAudioPath = audioFile.absolutePath,
            lifecycle = ArtifactLifecycle.RECORDING,
            lastCheckpointTimestamp = System.currentTimeMillis() - 70_000 // 70s old (> 10s)
        )
        
        every { userSessionManager.activeDraftId } returns flowOf(draftId)
        coEvery { draftDao.getActiveRecordings("user_1") } returns listOf(activeDraft)
        coEvery { draftDao.getDraftsByLifecycle(any(), any()) } returns emptyList()
        coEvery { draftDao.getAllDrafts() } returns listOf(activeDraft)

        // Act
        recordingRepository.recoverInterruptedDrafts()

        // Assert: No recovery action taken
        verify(exactly = 0) { wavRecoveryManager.recover(any(), any()) }
        coVerify(exactly = 0) { draftDao.update(any(), isRecovery = true) }
    }

    @Test
    fun `Active AAC draft should be skipped by recovery regardless of timestamp`() = runTest(testDispatcher) {
        // Arrange
        val draftId = "active_aac"
        val aacDraft = ArtifactDraftEntity(
            id = draftId,
            userId = "user_1",
            lifecycle = ArtifactLifecycle.RECORDING,
            mimeType = "audio/mp4",
            localAudioPath = "/tmp/audio.m4a",
            lastCheckpointTimestamp = System.currentTimeMillis() - 120_000 // 2 mins old (> 10s)
        )
        
        every { userSessionManager.activeDraftId } returns flowOf(draftId)
        coEvery { draftDao.getActiveRecordings("user_1") } returns listOf(aacDraft)
        coEvery { draftDao.getDraftsByLifecycle(any(), any()) } returns emptyList()
        coEvery { draftDao.getAllDrafts() } returns listOf(aacDraft)

        // Act
        recordingRepository.recoverInterruptedDrafts()

        // Assert: No recovery action taken
        coVerify(exactly = 0) { draftDao.update(any(), isRecovery = true) }
    }

    @Test
    fun `Null activeDraftId should not block recovery of stale drafts`() = runTest(testDispatcher) {
        // Arrange
        val draftId = "stale_wav"
        val audioFile = File(testDraftsDir, "draft_$draftId/audio.wav").apply { 
            parentFile.mkdirs()
            writeBytes(ByteArray(1000) { 0x01 }) 
        }
        val staleDraft = ArtifactDraftEntity(
            id = draftId,
            userId = "user_1",
            localAudioPath = audioFile.absolutePath,
            lifecycle = ArtifactLifecycle.RECORDING,
            lastCheckpointTimestamp = System.currentTimeMillis() - 70_000,
            durableBytes = 500L
        )
        
        every { userSessionManager.activeDraftId } returns flowOf(null)
        coEvery { draftDao.getActiveRecordings("user_1") } returns listOf(staleDraft)
        coEvery { draftDao.getDraftsByLifecycle(any(), any()) } returns emptyList()
        coEvery { draftDao.getAllDrafts() } returns listOf(staleDraft)
        every { wavRecoveryManager.recover(any(), any()) } returns WavRecoveryManager.RecoveryResult.REPAIRED

        // Act
        recordingRepository.recoverInterruptedDrafts()

        // Assert: Recovery proceeds
        coVerify { draftDao.update(any(), isRecovery = true) }
    }
}
