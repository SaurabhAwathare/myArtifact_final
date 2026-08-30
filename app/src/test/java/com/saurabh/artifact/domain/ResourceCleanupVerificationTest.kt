package com.saurabh.artifact.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.audio.RetentionPolicy
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.LocalCleanupStatus
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.util.StorageManager
import com.saurabh.artifact.worker.CleanupWorker
import com.saurabh.artifact.audio.DraftDeletionManager
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.UploadTaskDao
import com.saurabh.artifact.data.local.ArtifactDao
import com.saurabh.artifact.data.local.UserSessionManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import dagger.Lazy
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ResourceCleanupVerificationTest {

    private lateinit var context: Context
    private lateinit var storageManager: StorageManager
    private lateinit var localDraftManager: LocalDraftManager
    private lateinit var cleanupManager: ArtifactCleanupManager
    private lateinit var recordingRepository: RecordingRepository
    
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val artifactDao = mockk<ArtifactDao>(relaxed = true)
    private val uploadTaskDao = mockk<UploadTaskDao>(relaxed = true)
    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val userSessionManager = mockk<UserSessionManager>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val startupCoordinator = mockk<com.saurabh.artifact.startup.StartupCoordinator>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    
    private lateinit var testDraftsDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        testDraftsDir = Files.createTempDirectory("test_drafts").toFile()
        
        every { authRepository.currentUserId } returns "user_1"
        every { userSessionManager.activeDraftId } returns flowOf(null)
        coEvery { startupCoordinator.awaitComponent(com.saurabh.artifact.startup.StartupComponent.DATABASE) } returns Unit
        
        storageManager = mockk<StorageManager> {
            every { draftsRootDirectory } returns testDraftsDir
            every { getDraftDirectory(any()) } answers { 
                File(testDraftsDir, "draft_${it.invocation.args[0]}").apply { mkdirs() } 
            }
            every { deleteDirectoryRecursively(any()) } answers { (it.invocation.args[0] as File).deleteRecursively() }
            every { legacyDraftsDirectory } returns File(context.filesDir, "legacy_drafts")
            every { waveformsDirectory } returns File(context.filesDir, "waveforms")
            every { transcriptsDirectory } returns File(context.filesDir, "transcripts")
            every { frozenAudioDirectory } returns File(context.filesDir, "frozen_audio")
        }

        localDraftManager = LocalDraftManager(storageManager)
        
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        cleanupManager = ArtifactCleanupManager(
            artifactRepository,
            authRepository,
            Lazy { draftDao },
            Lazy { uploadTaskDao },
            workManager
        )

        recordingRepository = RecordingRepository(
            draftDao = Lazy { draftDao },
            userRepository = userRepository,
            localDraftManager = localDraftManager,
            wavRecoveryManager = mockk(relaxed = true),
            cleanupManager = cleanupManager,
            userSessionManager = userSessionManager,
            draftsDatabase = Lazy { database },
            diagnosticLogger = diagnosticLogger
        )
        
        // Mock Room withTransaction extension
        mockkStatic("androidx.room.RoomDatabaseKt")
        val transactionCaptor = slot<suspend () -> Any>()
        coEvery { database.withTransaction(capture(transactionCaptor)) } coAnswers {
            transactionCaptor.captured.invoke()
        }
    }

    // 1. ZOMBIE CLEANUP
    @Test
    fun `Scenario 1 - Zombie Cleanup should identify and queue stale 0-duration drafts`() = runTest {
        // Arrange
        val staleDraft = ArtifactDraftEntity(
            id = "zombie_1",
            userId = "user_1",
            durationMs = 0L,
            durableBytes = 0L,
            updatedAt = System.currentTimeMillis() - (40 * 60 * 1000), // 40 mins ago (> 30 threshold)
            lifecycle = ArtifactLifecycle.RECORDING,
            localAudioPath = "/tmp/audio.m4a"
        )
        
        every { userRepository.getCurrentUserId() } returns "user_1"
        coEvery { draftDao.getAllDrafts() } returns listOf(staleDraft)
        coEvery { draftDao.getDraftsByLifecycle(ArtifactLifecycle.DELETING, "user_1") } returns emptyList()
        coEvery { draftDao.getDraftById("zombie_1", "user_1") } returns staleDraft

        // Act: recoverInterruptedDrafts triggers purgeZombieDrafts
        recordingRepository.recoverInterruptedDrafts()
        runCurrent()

        // Assert
        // Verify a worker was enqueued with the correct tag
        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager.getWorkInfosByTag("cleanup_zombie_1").get()
        
        assertFalse("Cleanup worker should be scheduled for zombie_1", workInfos.isEmpty())
    }

    // 2. ACTIVE DRAFT PROTECTION
    @Test
    fun `Scenario 2 - Active Draft Protection should preserve recent 0-duration drafts`() = runTest {
        // Arrange
        val recentDraft = ArtifactDraftEntity(
            id = "active_1",
            userId = "user_1",
            durationMs = 0L,
            updatedAt = System.currentTimeMillis() - (10 * 60 * 1000), // 10 mins ago (< 30 threshold)
            lifecycle = ArtifactLifecycle.RECORDING,
            localAudioPath = "/tmp/audio.m4a"
        )
        
        every { userRepository.getCurrentUserId() } returns "user_1"
        coEvery { draftDao.getAllDrafts() } returns listOf(recentDraft)
        coEvery { draftDao.getDraftById("active_1", "user_1") } returns recentDraft
        
        // Act
        recordingRepository.recoverInterruptedDrafts()
        runCurrent()

        // Assert
        val workInfos = WorkManager.getInstance(context).getWorkInfosByTag("cleanup_active_1").get()
        assertTrue("No cleanup worker should be scheduled for active draft", workInfos.isEmpty())
    }

    // 3. ORPHAN GRACE PERIOD
    @Test
    fun `Scenario 3 - Orphan Grace Period should preserve new physical folders without DB records`() {
        // Arrange
        val now = System.currentTimeMillis()
        val oldDir = File(testDraftsDir, "draft_old").apply { 
            mkdirs()
            setLastModified(now - (3 * 60 * 60 * 1000)) // 3 hours ago (> 2h threshold)
        }
        val newDir = File(testDraftsDir, "draft_new").apply { 
            mkdirs()
            setLastModified(now - (30 * 60 * 1000)) // 30 mins ago (< 2h threshold)
        }

        // Act: One valid draft to DB to avoid Sanity Gate
        val validDraft = ArtifactDraftEntity(id = "valid", userId = "u1", localAudioPath = File(testDraftsDir, "draft_valid/audio.m4a").absolutePath)
        File(testDraftsDir, "draft_valid").mkdirs()
        
        localDraftManager.reconcileStorage(listOf(validDraft))

        // Assert
        assertFalse("Old orphan should be deleted", oldDir.exists())
        assertTrue("New orphan should be preserved (grace period)", newDir.exists())
    }

    // 4. SANITY GATE
    @Test
    fun `Scenario 4 - Sanity Gate should abort reconciliation if DB is unexpectedly empty`() {
        // Arrange
        val orphanDir = File(testDraftsDir, "draft_orphan").apply { mkdirs() }
        orphanDir.setLastModified(System.currentTimeMillis() - (5 * 60 * 60 * 1000)) // Very old

        // Act: DB returns EMPTY list
        localDraftManager.reconcileStorage(emptyList())

        // Assert
        assertTrue("Sanity Gate should have preserved the folder", orphanDir.exists())
    }

    // 5. AUTOMATIC RETENTION SAFETY
    @Test
    fun `Scenario 5 - Automatic Retention Safety should never call remote delete`() = runTest {
        // Arrange
        val artifactId = "pub_1"
        val draft = ArtifactDraftEntity(
            id = artifactId, 
            userId = "user_1", 
            remoteArtifactId = "remote_1",
            localAudioPath = "/tmp/audio.m4a"
        )
        coEvery { draftDao.internalGetDraftByIdAgnostic(artifactId) } returns draft
        coEvery { draftDao.internalGetDraftByArtifactIdAgnostic(artifactId) } returns draft
        
        val worker = androidx.work.testing.TestListenableWorkerBuilder<CleanupWorker>(context)
            .setInputData(androidx.work.workDataOf(
                CleanupWorker.KEY_ARTIFACT_ID to artifactId,
                CleanupWorker.KEY_PURGE_REMOTE to false // AUTOMATIC RETENTION
            ))
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, params: androidx.work.WorkerParameters): ListenableWorker {
                    return CleanupWorker(
                        appContext, params, Lazy { draftDao }, Lazy { artifactDao }, Lazy { uploadTaskDao }, Lazy { database },
                        DraftDeletionManager(storageManager), storageManager, artifactRepository, startupCoordinator, diagnosticLogger
                    )
                }
            })
            .build()

        // Act
        worker.doWork()

        // Assert
        coVerify(exactly = 0) { artifactRepository.performRemoteDelete(any()) }
    }

    // 6. MANUAL DELETION
    @Test
    fun `Scenario 6 - Manual Deletion should trigger both local and remote removal`() = runTest {
        // Arrange
        val artifactId = "pub_1"
        val draft = ArtifactDraftEntity(
            id = artifactId, 
            userId = "user_1", 
            remoteArtifactId = "remote_1",
            localAudioPath = "/tmp/audio.m4a"
        )
        coEvery { draftDao.internalGetDraftByIdAgnostic(artifactId) } returns draft
        coEvery { draftDao.internalGetDraftByArtifactIdAgnostic(artifactId) } returns draft
        coEvery { artifactRepository.performRemoteDelete("remote_1") } returns Result.success(Unit)

        val worker = androidx.work.testing.TestListenableWorkerBuilder<CleanupWorker>(context)
            .setInputData(androidx.work.workDataOf(
                CleanupWorker.KEY_ARTIFACT_ID to artifactId,
                CleanupWorker.KEY_PURGE_REMOTE to true // MANUAL DELETE
            ))
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, params: androidx.work.WorkerParameters): ListenableWorker {
                    return CleanupWorker(
                        appContext, params, Lazy { draftDao }, Lazy { artifactDao }, Lazy { uploadTaskDao }, Lazy { database },
                        DraftDeletionManager(storageManager), storageManager, artifactRepository, startupCoordinator, diagnosticLogger
                    )
                }
            })
            .build()

        // Act
        worker.doWork()

        // Assert
        coVerify(exactly = 1) { artifactRepository.performRemoteDelete("remote_1") }
    }
}
