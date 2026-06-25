package com.saurabh.artifact.repository

import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.DraftStatus
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import androidx.room.withTransaction

class RecordingFinalizationIdempotencyTest {
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val appDatabase = mockk<AppDatabase>(relaxed = true)
    private val recordingRepository = RecordingRepository(
        draftDao = draftDao,
        userRepository = mockk(),
        localDraftManager = mockk(),
        wavRecoveryManager = mockk(),
        deletionManager = mockk(),
        cleanupManager = mockk(),
        draftsDatabase = appDatabase
    )

    @Before
    fun setup() {
        // Mock Room transaction
        mockkStatic("androidx.room.RoomDatabaseKt")
        val transactionLambda = slot<suspend () -> Any>()
        coEvery { appDatabase.withTransaction(capture(transactionLambda)) } coAnswers {
            transactionLambda.captured.invoke()
        }
    }

    @Test
    fun `finalizeRecording should be idempotent when called with same data`() = runBlocking {
        val draftId = "test-draft"
        val duration = 5000L
        val bytes = 10000L
        
        val existingDraft = ArtifactDraftEntity(
            id = draftId,
            localAudioPath = "/path/audio.wav",
            lifecycle = ArtifactLifecycle.PROCESSING,
            durationMs = duration,
            durableBytes = bytes,
            status = DraftStatus()
        )

        coEvery { draftDao.getDraftById(draftId) } returns existingDraft
        
        // Call finalizeRecording again with SAME data
        val result = recordingRepository.finalizeRecording(draftId, duration, bytes)

        // Verify: success returned
        assert(result.isSuccess)
        
        // Verify: update was NOT called (idempotent skip)
        coVerify(exactly = 0) { draftDao.update(any()) }
    }

    @Test
    fun `finalizeRecording should block regression when called on advanced draft`() = runBlocking {
        val draftId = "test-draft"
        
        // Existing draft is already at METADATA_REQUIRED
        val advancedDraft = ArtifactDraftEntity(
            id = draftId,
            localAudioPath = "/path/audio.wav",
            lifecycle = ArtifactLifecycle.METADATA_REQUIRED,
            durationMs = 5000L,
            durableBytes = 10000L,
            status = DraftStatus()
        )

        coEvery { draftDao.getDraftById(draftId) } returns advancedDraft
        
        // Try to "finalize" back to PROCESSING with DIFFERENT data
        val result = recordingRepository.finalizeRecording(draftId, 6000L, 12000L)

        // Verify: failure returned
        assert(result.isFailure)
        assert(result.exceptionOrNull()?.message?.contains("Invalid transition") == true)
        
        // Verify: update was NOT called
        coVerify(exactly = 0) { draftDao.update(any()) }
    }
}
