package com.saurabh.artifact.repository

import android.util.Log
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.audio.WavRecoveryManager
import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.audio.DraftDeletionManager
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.repository.UserRepository
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import dagger.Lazy
import kotlin.Result as KResult

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingRepositoryTest {
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val localDraftManager = mockk<LocalDraftManager>(relaxed = true)
    private val wavRecoveryManager = mockk<WavRecoveryManager>(relaxed = true)
    private val deletionManager = mockk<DraftDeletionManager>(relaxed = true)
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
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

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
    fun `recoverInterruptedDrafts should detect stalled PROCESSING drafts`() = runTest {
        val now = System.currentTimeMillis()
        val stalledDraft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns "stalled"
            every { lifecycle } returns ArtifactLifecycle.PROCESSING
            every { updatedAt } returns now - (16 * 60 * 1000L) // 16 mins ago (> 15m threshold)
            every { lastRecoveryAttemptAt } returns 0L
        }
        val recentDraft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns "recent"
            every { lifecycle } returns ArtifactLifecycle.PROCESSING
            every { updatedAt } returns now - (5 * 60 * 1000L) // 5 mins ago
            every { lastRecoveryAttemptAt } returns 0L
        }

        coEvery { draftDao.getDraftsByLifecycle(ArtifactLifecycle.PROCESSING, any()) } returns listOf(stalledDraft, recentDraft)
        coEvery { draftDao.getActiveRecordings(any()) } returns emptyList()

        val result = repository.recoverInterruptedDrafts().getOrThrow()

        assert(result.size == 1)
        assert(result[0].id == "stalled")
    }

    @Test
    fun `recoverInterruptedDrafts should respect recovery cooldown`() = runTest {
        val now = System.currentTimeMillis()
        val coolingDraft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns "cooling"
            every { lifecycle } returns ArtifactLifecycle.PROCESSING
            every { updatedAt } returns now - (20 * 60 * 1000L) // Stale
            every { lastRecoveryAttemptAt } returns now - (2 * 60 * 1000L) // Recovered 2 mins ago (< 5m cooldown)
        }

        coEvery { draftDao.getDraftsByLifecycle(ArtifactLifecycle.PROCESSING, any()) } returns listOf(coolingDraft)
        coEvery { draftDao.getActiveRecordings(any()) } returns emptyList()

        val result = repository.recoverInterruptedDrafts().getOrThrow()

        assert(result.isEmpty())
    }

    @Test
    fun `recoverInterruptedDrafts boundary test - just below 15m`() = runTest {
        val now = System.currentTimeMillis()
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns "boundary"
            every { lifecycle } returns ArtifactLifecycle.PROCESSING
            every { updatedAt } returns now - (14 * 60 * 1000L + 59_000L) // 14m 59s
            every { lastRecoveryAttemptAt } returns 0L
        }

        coEvery { draftDao.getDraftsByLifecycle(ArtifactLifecycle.PROCESSING, any()) } returns listOf(draft)
        coEvery { draftDao.getActiveRecordings(any()) } returns emptyList()
        
        val result = repository.recoverInterruptedDrafts().getOrThrow()
        assert(result.isEmpty())
    }

    @Test
    fun `recoverInterruptedDrafts boundary test - just above 15m`() = runTest {
        val now = System.currentTimeMillis()
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns "boundary"
            every { lifecycle } returns ArtifactLifecycle.PROCESSING
            every { updatedAt } returns now - (15 * 60 * 1000L + 1000L) // 15m 01s
            every { lastRecoveryAttemptAt } returns 0L
        }

        coEvery { draftDao.getDraftsByLifecycle(ArtifactLifecycle.PROCESSING, any()) } returns listOf(draft)
        coEvery { draftDao.getActiveRecordings(any()) } returns emptyList()
        
        val result = repository.recoverInterruptedDrafts().getOrThrow()
        assert(result.size == 1)
    }

    @Test
    fun `recoverInterruptedDrafts should resume DELETING drafts`() = runTest {
        val deletingDraft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns "deleting_1"
            every { lifecycle } returns ArtifactLifecycle.DELETING
        }

        coEvery { draftDao.getDraftsByLifecycle(ArtifactLifecycle.DELETING, any()) } returns listOf(deletingDraft)
        coEvery { draftDao.getActiveRecordings(any()) } returns emptyList()
        coEvery { draftDao.getDraftsByLifecycle(ArtifactLifecycle.PROCESSING, any()) } returns emptyList()

        repository.recoverInterruptedDrafts()

        coVerify(exactly = 1) { cleanupManager.deleteDraft("deleting_1") }
    }

    @Test
    fun `recoverInterruptedDrafts should NOT purge drafts that are recoverable despite 0 durableBytes`() = runTest {
        val now = System.currentTimeMillis()
        val draftId = "recoverable_zero_byte"
        
        // 1. Setup a "database" list to simulate Room persistence
        val dbDrafts = mutableListOf<ArtifactDraftEntity>()

        // Create a real temporary file to satisfy the File(path).exists() check
        val tempFile = File.createTempFile("recoverable", ".wav")
        tempFile.writeBytes(ByteArray(10044)) // 10000 bytes data + 44 header
        val tempPath = tempFile.absolutePath

        val zombieLookingDraft = ArtifactDraftEntity(
            id = draftId,
            userId = TEST_USER_ID,
            localAudioPath = tempPath,
            lifecycle = ArtifactLifecycle.RECORDING,
            durableBytes = 0L,
            durationMs = 0L,
            updatedAt = now - (60 * 60 * 1000L), // 1 hour ago
            lastCheckpointTimestamp = now - (60 * 60 * 1000L),
            status = com.saurabh.artifact.model.DraftStatus()
        )
        
        dbDrafts.add(zombieLookingDraft)

        coEvery { draftDao.getActiveRecordings(TEST_USER_ID) } returns listOf(zombieLookingDraft)
        coEvery { draftDao.getAllDrafts() } answers { dbDrafts.toList() }
        coEvery { draftDao.getDraftsByLifecycle(any(), any()) } returns emptyList()
        
        coEvery { draftDao.update(any(), isRecovery = true) } answers {
            val updated = it.invocation.args[0] as ArtifactDraftEntity
            dbDrafts.clear()
            dbDrafts.add(updated)
        }

        // 2. Mock recovery success
        every { wavRecoveryManager.recover(any(), any()) } returns WavRecoveryManager.RecoveryResult.REPAIRED
        
        // 3. Execute recovery
        repository.recoverInterruptedDrafts()

        // 4. VERIFY: cleanupManager.deleteDraft was NOT called for this draft
        coVerify(exactly = 0) { cleanupManager.deleteDraft(draftId) }
        
        // 5. VERIFY: draft in DB was updated to non-zero bytes/duration
        val finalDraft = dbDrafts.first()
        assert(finalDraft.id == draftId)
        assert(finalDraft.lifecycle == ArtifactLifecycle.PROCESSING)
        assert(finalDraft.durableBytes == 10000L) // 10044 - 44
        
        tempFile.delete()
    }
}
