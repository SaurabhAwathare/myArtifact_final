package com.saurabh.artifact.audio

import com.saurabh.artifact.data.local.RecordingStatus
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.domain.PublishingOrchestrator
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.util.StorageManager
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingFinalizationHardeningTest {

    private val recordingRepository = mockk<RecordingRepository>(relaxed = true)
    private val publishingOrchestrator = mockk<PublishingOrchestrator>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val userSessionManager = mockk<UserSessionManager>(relaxed = true)
    private val storageManager = mockk<StorageManager>(relaxed = true)
    private val audioRecorder = mockk<AudioRecorder>(relaxed = true)

    private lateinit var service: RecordingService
    
    // We'll use a test dispatcher
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        service = spyk(RecordingService())
        
        // Inject dependencies into the spied service
        val fields = RecordingService::class.java.declaredFields
        fields.forEach { field ->
            field.isAccessible = true
            when (field.name) {
                "recordingRepository" -> field.set(service, recordingRepository)
                "publishingOrchestrator" -> field.set(service, publishingOrchestrator)
                "diagnosticLogger" -> field.set(service, diagnosticLogger)
                "draftDao" -> field.set(service, mockk<dagger.Lazy<DraftDao>> { every { get() } returns draftDao })
                "userSessionManager" -> field.set(service, userSessionManager)
                "storageManager" -> field.set(service, storageManager)
                "audioRecorder" -> field.set(service, audioRecorder)
            }
        }
    }

    @Test
    fun `stopRecording with 0 duration should transition to FAILED and delete empty file`() = runTest(testDispatcher) {
        val tempFile = File.createTempFile("test_short", ".wav")
        // Write exactly the WAV header
        tempFile.writeBytes(ByteArray(44))
        
        val stateField = RecordingService.Companion::class.java.getDeclaredField("_recordingState")
        stateField.isAccessible = true
        val stateFlow = stateField.get(null) as MutableStateFlow<RecordingService.Companion.RecordingState>
        stateFlow.value = RecordingService.Companion.RecordingState(
            status = RecordingStatus.RECORDING,
            draftId = "draft-123",
            outputFile = tempFile
        )

        // Mock calculateDurationMs to return 0
        mockkObject(WavHeaderUtils)
        every { WavHeaderUtils.calculateDurationMs(any(), any(), any(), any()) } returns 0L

        service.stopRecording()
        
        // Finalization happens in a coroutine launched in serviceScope (Main).
        // Since we are using UnconfinedTestDispatcher for runTest and spyk uses it too if configured, 
        // we might need to advance time or yield.
        
        // RecordingService uses serviceScope = CoroutineScope(Dispatchers.Main + ...)
        // We should mock Dispatchers.Main
        Dispatchers.setMain(testDispatcher)

        // Give it a moment to run the launched coroutine
        yield() 
        
        assertEquals(RecordingStatus.FAILED, stateFlow.value.status)
        assertFalse(tempFile.exists()) // Should be deleted as it's just a header
        
        unmockkObject(WavHeaderUtils)
        Dispatchers.resetMain()
    }
}
