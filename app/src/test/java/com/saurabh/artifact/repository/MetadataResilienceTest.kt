package com.saurabh.artifact.repository

import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.DraftManifest
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.audio.WavRecoveryManager
import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import dagger.Lazy

@OptIn(ExperimentalCoroutinesApi::class)
class MetadataResilienceTest {
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val localDraftManager = mockk<LocalDraftManager>(relaxed = true)
    private val wavRecoveryManager = mockk<WavRecoveryManager>(relaxed = true)
    private val cleanupManager = mockk<ArtifactCleanupManager>(relaxed = true)
    private val draftsDatabase = mockk<AppDatabase>(relaxed = true)
    private val userSessionManager = mockk<com.saurabh.artifact.data.local.UserSessionManager>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var repository: RecordingRepository

    private companion object {
        private const val TEST_USER_ID = "test-user-id"
    }

    @Before
    fun setup() {
        every { userRepository.getCurrentUserId() } returns TEST_USER_ID

        repository = RecordingRepository(
            draftDao = Lazy { draftDao },
            userRepository = userRepository,
            localDraftManager = localDraftManager,
            wavRecoveryManager = wavRecoveryManager,
            cleanupManager = cleanupManager,
            userSessionManager = userSessionManager,
            draftsDatabase = Lazy { draftsDatabase },
            diagnosticLogger = diagnosticLogger
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `recoverInterruptedDrafts should rediscover and re-index drafts with valid manifests`() = runTest {
        val orphanedId = "orphaned-123"
        val manifest = DraftManifest(
            draftId = orphanedId,
            userId = TEST_USER_ID,
            createdAt = System.currentTimeMillis(),
            mimeType = "audio/wav"
        )
        
        // 1. Database is empty
        coEvery { draftDao.getAllDraftsByUserId(TEST_USER_ID) } returns emptyList()
        
        // 2. Filesystem has one orphaned draft
        every { localDraftManager.findOrphanedDraftDirectories(emptySet()) } returns listOf(orphanedId)
        every { localDraftManager.readManifest(orphanedId) } returns manifest
        
        // Mock file existence (WAV exists, M4A does not)
        val wavPath = "/storage/Artifact/Drafts/draft_$orphanedId/audio.wav"
        every { localDraftManager.createDraftFile(orphanedId, "wav") } returns File(wavPath)
        every { localDraftManager.createDraftFile(orphanedId, "m4a") } returns File("/non/existent/audio.m4a")
        
        // Mock createDraft dependency
        coEvery { draftDao.getDraftById(any(), any()) } returns null
        
        // 3. Trigger recovery
        repository.recoverInterruptedDrafts()
        
        // 4. Verify re-indexing: createDraft should have been called (which calls draftDao.insert)
        coVerify(exactly = 1) { 
            draftDao.insert(match { it.id == orphanedId && it.userId == TEST_USER_ID && it.localAudioPath == wavPath })
        }
        
        // Verify manifest was also rewritten (idempotent but confirmed)
        verify { localDraftManager.writeManifest(orphanedId, TEST_USER_ID, any(), "audio/wav") }
    }

    @Test
    fun `recoverInterruptedDrafts should reject drafts with mismatched userId in manifest`() = runTest {
        val orphanedId = "other-users-draft"
        val manifest = DraftManifest(
            draftId = orphanedId,
            userId = "WRONG-USER",
            createdAt = System.currentTimeMillis(),
            mimeType = "audio/wav"
        )
        
        coEvery { draftDao.getAllDraftsByUserId(TEST_USER_ID) } returns emptyList()
        every { localDraftManager.findOrphanedDraftDirectories(emptySet()) } returns listOf(orphanedId)
        every { localDraftManager.readManifest(orphanedId) } returns manifest
        
        repository.recoverInterruptedDrafts()
        
        // Should NOT be inserted into the database
        coVerify(exactly = 0) { draftDao.insert(any()) }
    }

    @Test
    fun `recoverInterruptedDrafts should reject orphaned directories without manifests`() = runTest {
        val orphanedId = "no-manifest-here"
        
        coEvery { draftDao.getAllDraftsByUserId(TEST_USER_ID) } returns emptyList()
        every { localDraftManager.findOrphanedDraftDirectories(emptySet()) } returns listOf(orphanedId)
        every { localDraftManager.readManifest(orphanedId) } returns null
        
        repository.recoverInterruptedDrafts()
        
        coVerify(exactly = 0) { draftDao.insert(any()) }
    }
}
