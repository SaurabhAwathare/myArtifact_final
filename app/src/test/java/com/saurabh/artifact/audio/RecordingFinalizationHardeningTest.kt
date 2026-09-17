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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

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
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher
        every { Dispatchers.Default } returns testDispatcher
        Dispatchers.setMain(testDispatcher)

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

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Dispatchers::class)
    }

    private fun getRecordingStateFlow(): MutableStateFlow<RecordingService.Companion.RecordingState> {
        val field = try {
            RecordingService::class.java.getDeclaredField("_recordingState")
        } catch (_: NoSuchFieldException) {
            RecordingService.Companion::class.java.getDeclaredField("_recordingState")
        }
        field.isAccessible = true
        val target = if (Modifier.isStatic(field.modifiers)) null else RecordingService.Companion
        @Suppress("UNCHECKED_CAST")
        return field.get(target) as MutableStateFlow<RecordingService.Companion.RecordingState>
    }

    @Test
    fun `stopRecording with 0 duration should transition to FAILED and delete empty file`() = runTest(testDispatcher) {
        val tempFile = File.createTempFile("test_short", ".wav")
        // Write exactly the WAV header
        tempFile.writeBytes(ByteArray(44))
        
        val stateFlow = getRecordingStateFlow()
        stateFlow.value = RecordingService.Companion.RecordingState(
            status = RecordingStatus.RECORDING,
            draftId = "draft-123",
            outputFile = tempFile
        )

        // Mock calculateDurationMs to return 0
        mockkObject(WavHeaderUtils)
        every { WavHeaderUtils.calculateDurationMs(any(), any(), any(), any()) } returns 0L

        service.stopRecording()
        
        advanceUntilIdle()
        
        assertEquals(RecordingStatus.FAILED, stateFlow.value.status)
        assertFalse(tempFile.exists()) // Should be deleted as it's just a header
        
        unmockkObject(WavHeaderUtils)
    }
}
