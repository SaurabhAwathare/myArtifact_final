package com.saurabh.artifact.ui.recording.warning

import androidx.lifecycle.SavedStateHandle
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.data.local.RecordingStatus
import com.saurabh.artifact.util.StorageManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreRecordingViewModelTest {

    private lateinit var viewModel: PreRecordingViewModel
    private val recordingSessionManager: RecordingSessionManager = mockk(relaxed = true)
    private val storageManager: StorageManager = mockk(relaxed = true)
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()

    private val testDispatcher = StandardTestDispatcher()
    private val sessionStateFlow = MutableStateFlow(RecordingSessionManager.SessionState())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { recordingSessionManager.sessionState } returns sessionStateFlow
        every { storageManager.availableStorageMb } returns 2048L
        every { storageManager.isStorageAvailable() } returns true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has zero remaining seconds and action is immediately available`() = runTest {
        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)

        // Stage 3.6.3a: Countdown removed, remaining seconds is 0, action available immediately
        assertEquals(0, viewModel.uiState.value.remainingSeconds)
        assertTrue("Action must be immediately available", viewModel.canBeginRecording())
        verify { recordingSessionManager.cancelRitual() }
    }

    @Test
    fun `cancel invokes recordingSessionManager cancelRitual and clears state`() = runTest {
        savedStateHandle[PreRecordingViewModel.KEY_RITUAL_END_TIME] = 123456789L
        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)

        viewModel.cancel()

        verify(atLeast = 1) { recordingSessionManager.cancelRitual() }
        assertFalse(savedStateHandle.contains(PreRecordingViewModel.KEY_RITUAL_END_TIME))
    }

    @Test
    fun `onBeginRecording skips ritual and executes proceed callback`() = runTest {
        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)

        var proceedCalled = false
        viewModel.onBeginRecording {
            proceedCalled = true
        }

        assertTrue("Proceed callback should be executed", proceedCalled)
        verify { recordingSessionManager.skipRitual() }
    }

    @Test
    fun `repeated onBeginRecording taps do not initialize multiple sessions`() = runTest {
        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)

        var callCount = 0
        viewModel.onBeginRecording { callCount++ }
        viewModel.onBeginRecording { callCount++ }
        viewModel.onBeginRecording { callCount++ }

        assertEquals("Proceed callback must be called exactly once despite repeated taps", 1, callCount)
    }

    @Test
    fun `cannot begin recording when storage is critically low`() = runTest {
        every { storageManager.availableStorageMb } returns 400L
        every { storageManager.isStorageAvailable() } returns false

        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)

        assertFalse("Recording must not be allowed when storage is critical", viewModel.canBeginRecording())

        var proceedCalled = false
        viewModel.onBeginRecording {
            proceedCalled = true
        }
        assertFalse("Proceed callback must not be invoked when storage is critical", proceedCalled)
    }

    @Test
    fun `low storage flag is propagated when available storage is below 1GB threshold`() = runTest {
        // Less than 1024MB (1GB) but above critical minimum
        every { storageManager.availableStorageMb } returns 900L
        every { storageManager.isStorageAvailable() } returns true

        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)

        assertTrue("Storage low warning must be active when below 1GB threshold", viewModel.uiState.value.isStorageLow)
        assertFalse("Storage must not be critical when above minimum", viewModel.uiState.value.isStorageCritical)
    }

    @Test
    fun `auto-navigation triggers on RECORDING status`() = runTest {
        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)

        val events = mutableListOf<PreRecordingWarningEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.eventFlow.collect { events.add(it) }
        }

        sessionStateFlow.emit(RecordingSessionManager.SessionState(status = RecordingStatus.RECORDING))
        runCurrent()

        assertTrue(
            "Event should be emitted on RECORDING status",
            events.any { it is PreRecordingWarningEvent.NavigateToRecording }
        )
        job.cancel()
    }

    @Test
    fun `auto-navigation triggers on PREPARING status`() = runTest {
        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)

        val events = mutableListOf<PreRecordingWarningEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.eventFlow.collect { events.add(it) }
        }

        sessionStateFlow.emit(RecordingSessionManager.SessionState(status = RecordingStatus.PREPARING))
        runCurrent()

        assertTrue(
            "Should navigate even during PREPARING state",
            events.any { it is PreRecordingWarningEvent.NavigateToRecording }
        )
        job.cancel()
    }
}
