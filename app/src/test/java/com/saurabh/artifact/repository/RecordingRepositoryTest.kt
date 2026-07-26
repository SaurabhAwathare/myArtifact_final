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
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var repository: RecordingRepository

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        repository = RecordingRepository(
            draftDao = Lazy { draftDao },
            userRepository = userRepository,
            localDraftManager = localDraftManager,
            wavRecoveryManager = wavRecoveryManager,
            cleanupManager = cleanupManager,
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

        coEvery { draftDao.getDraftsByLifecycle(ArtifactLifecycle.PROCESSING) } returns listOf(stalledDraft, recentDraft)
        coEvery { draftDao.getActiveRecordings() } returns emptyList()

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

        coEvery { draftDao.getDraftsByLifecycle(ArtifactLifecycle.PROCESSING) } returns listOf(coolingDraft)
        coEvery { draftDao.getActiveRecordings() } returns emptyList()

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

        coEvery { draftDao.getDraftsByLifecycle(ArtifactLifecycle.PROCESSING) } returns listOf(draft)
        
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

        coEvery { draftDao.getDraftsByLifecycle(ArtifactLifecycle.PROCESSING) } returns listOf(draft)
        
        val result = repository.recoverInterruptedDrafts().getOrThrow()
        assert(result.size == 1)
    }

    @Test
    fun `recoverInterruptedDrafts should resume DELETING drafts`() = runTest {
        val deletingDraft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns "deleting_1"
            every { lifecycle } returns ArtifactLifecycle.DELETING
        }

        coEvery { draftDao.getDraftsByLifecycle(ArtifactLifecycle.DELETING) } returns listOf(deletingDraft)
        coEvery { draftDao.getActiveRecordings() } returns emptyList()
        coEvery { draftDao.getDraftsByLifecycle(ArtifactLifecycle.PROCESSING) } returns emptyList()

        repository.recoverInterruptedDrafts()

        coVerify(exactly = 1) { cleanupManager.deleteDraft("deleting_1") }
    }

    @Test
    fun `updateTranscriptionResult should propagate data to DAO`() = runTest {
        val id = "draft_1"
        val path = "/path/to/transcript.json"
        val json = """[{"text":"test"}]"""
        
        repository.updateTranscriptionResult(id, path, json, null, null)

        coVerify {
            draftDao.updateTranscriptionResult(
                id = id,
                localTranscriptPath = path,
                transcriptSegmentsJson = any(),
                emotionalTone = null,
                primaryStyle = null,
                timestamp = any()
            )
        }
    }
}
