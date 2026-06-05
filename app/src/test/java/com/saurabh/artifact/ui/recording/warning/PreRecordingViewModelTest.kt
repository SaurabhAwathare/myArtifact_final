package com.saurabh.artifact.ui.recording.warning

import androidx.lifecycle.SavedStateHandle
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.data.local.RecordingStatus
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreRecordingViewModelTest {

    private lateinit var viewModel: PreRecordingViewModel
    private val recordingSessionManager: RecordingSessionManager = mockk(relaxed = true)
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
    
    private val testDispatcher = StandardTestDispatcher()
    
    private val sessionStateFlow = MutableStateFlow(RecordingSessionManager.SessionState())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { recordingSessionManager.sessionState } returns sessionStateFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state uses RITUAL_DURATION_SECONDS when no saved state`() = runTest {
        viewModel = PreRecordingViewModel(recordingSessionManager, savedStateHandle)
        
        assertEquals(10, viewModel.uiState.value.remainingSeconds)
    }

    @Test
    fun `initial state calculates remaining seconds from SavedStateHandle`() = runTest {
        val futureTime = System.currentTimeMillis() + 5000 // 5 seconds from now
        savedStateHandle["ritual_end_time"] = futureTime
        
        viewModel = PreRecordingViewModel(recordingSessionManager, savedStateHandle)
        
        // Should be approximately 5 (allowing for minor execution delay)
        val remaining = viewModel.uiState.value.remainingSeconds
        assertTrue("Remaining should be around 5, but was $remaining", remaining in 4..5)
    }

    @Test
    fun `auto-navigation triggers on RECORDING status`() = runTest {
        viewModel = PreRecordingViewModel(recordingSessionManager, savedStateHandle)
        
        val events = mutableListOf<PreRecordingWarningEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.eventFlow.collect { events.add(it) }
        }
        
        // This triggers the observer in init block
        sessionStateFlow.emit(RecordingSessionManager.SessionState(status = RecordingStatus.RECORDING))
        runCurrent()
        
        assertTrue("Event should be emitted on RECORDING status", 
            events.any { it is PreRecordingWarningEvent.NavigateToRecording })
        job.cancel()
    }

    @Test
    fun `auto-navigation triggers on PREPARING status`() = runTest {
        viewModel = PreRecordingViewModel(recordingSessionManager, savedStateHandle)
        
        val events = mutableListOf<PreRecordingWarningEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.eventFlow.collect { events.add(it) }
        }
        
        sessionStateFlow.emit(RecordingSessionManager.SessionState(status = RecordingStatus.PREPARING))
        runCurrent()
        
        assertTrue("Should navigate even during PREPARING state", 
            events.any { it is PreRecordingWarningEvent.NavigateToRecording })
        job.cancel()
    }

    @Test
    fun `skipCountdown updates SavedStateHandle and Manager`() = runTest {
        viewModel = PreRecordingViewModel(recordingSessionManager, savedStateHandle)
        
        viewModel.skipCountdown()
        
        verify { recordingSessionManager.skipRitual() }
        val savedEndTime = savedStateHandle.get<Long>("ritual_end_time")!!
        assertTrue(savedEndTime <= System.currentTimeMillis())
    }
}
