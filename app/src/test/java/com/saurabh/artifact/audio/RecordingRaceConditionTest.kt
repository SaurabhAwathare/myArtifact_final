package com.saurabh.artifact.audio

import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.RecordingStatus
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.domain.PublishingOrchestrator
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.util.StorageManager
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingRaceConditionTest {

    private lateinit var service: RecordingService
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val recordingRepository = mockk<RecordingRepository>(relaxed = true)
    private val userSessionManager = mockk<UserSessionManager>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private val publishingOrchestrator = mockk<PublishingOrchestrator>(relaxed = true)
    private val storageManager = mockk<StorageManager>(relaxed = true)
    private val localDraftManager = mockk<LocalDraftManager>(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        service = RecordingService().apply {
            this.draftDao = this@RecordingRaceConditionTest.draftDao
            this.artifactRepository = this@RecordingRaceConditionTest.artifactRepository
            this.recordingRepository = this@RecordingRaceConditionTest.recordingRepository
            this.userSessionManager = this@RecordingRaceConditionTest.userSessionManager
            this.diagnosticLogger = this@RecordingRaceConditionTest.diagnosticLogger
            this.publishingOrchestrator = this@RecordingRaceConditionTest.publishingOrchestrator
            this.storageManager = this@RecordingRaceConditionTest.storageManager
            this.localDraftManager = this@RecordingRaceConditionTest.localDraftManager
        }
        
        coEvery { recordingRepository.finalizeRecording(any(), any(), any()) } returns Result.success(Unit)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `session hijacking race condition - Stop resumes after Cancel and New Start`() = testScope.runTest {
        // Initial state: Recording A is active
        val draftA = "draft-A"
        val fileA = mockk<File>(relaxed = true)
        every { fileA.exists() } returns true
        every { fileA.length() } returns 1024L
        every { fileA.absolutePath } returns "path/A.wav"
        
        setServiceState(RecordingStatus.RECORDING, draftA, fileA)

        // 1. Trigger Stop Recording A
        service.stopRecording()
        
        // Let it reach the delay
        runCurrent()
        advanceTimeBy(100.milliseconds) 
        
        // 2. Trigger Cancel Recording A
        // With FIX: cancelRecording will wait for the lock held by stopRecording
        service.cancelRecording()
        runCurrent()
        
        // Verify state is NOT IDLE yet because cancel is waiting for stopMutex
        // Actually, stopRecording is still in delay(500) holding the lock.
        assertNotEquals("Cancel should be waiting for stopMutex", RecordingStatus.IDLE, RecordingService.recordingState.value.status)
        
        // 3. Advance time to let Stop A resume
        advanceTimeBy(500.milliseconds)
        runCurrent()
        
        // Stop A should finish. Status should be COMPLETED.
        assertEquals(RecordingStatus.COMPLETED, RecordingService.recordingState.value.status)
        
        // Now cancelRecording acquires the lock, but sees status is COMPLETED, so it ignores.
        runCurrent()
        assertEquals("Cancel should have ignored as status is COMPLETED", RecordingStatus.COMPLETED, RecordingService.recordingState.value.status)
        
        // Verify finalizeRecording was called with A's data
        coVerify(exactly = 1) { recordingRepository.finalizeRecording(draftA, any(), any()) }
    }

    @Test
    fun `cancelRecording should capture identifiers before cleanup`() = testScope.runTest {
        val draftId = "cancel-draft"
        val file = mockk<File>(relaxed = true)
        every { file.exists() } returns true
        every { file.absolutePath } returns "cancel.wav"
        
        setServiceState(RecordingStatus.RECORDING, draftId, file)
        
        service.cancelRecording()
        runCurrent()
        advanceTimeBy(100.milliseconds) // Since it's synced now, we might need a tick
        runCurrent()
        
        // Verify that draftDao.delete was called with the correct draft ID
        coVerify { draftDao.delete(match { it.id == draftId }) }
        // Verify file deletion
        verify { file.delete() }
    }

    private fun assertNotEquals(message: String, illegal: Any?, actual: Any?) {
        if (illegal == actual) {
            throw AssertionError("$message. Expected not to be $illegal but was $actual")
        }
    }

    private fun setServiceState(status: RecordingStatus, draftId: String, outputFile: File?) {
        val field = RecordingService.Companion::class.java.getDeclaredField("_recordingState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(null) as MutableStateFlow<RecordingService.Companion.RecordingState>
        stateFlow.value = RecordingService.Companion.RecordingState(
            status = status,
            draftId = draftId,
            outputFile = outputFile
        )
    }
}
