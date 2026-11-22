package com.saurabh.artifact.ui.recording.warning

import androidx.lifecycle.SavedStateHandle
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.data.local.RecordingStatus
import com.saurabh.artifact.util.StorageManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
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

        every { recordingSessionManager.startRitual(any()) } answers {
            val sec = firstArg<Int>()
            sessionStateFlow.update { it.copy(ritualRemainingSeconds = sec) }
        }
        every { recordingSessionManager.cancelRitual() } answers {
            sessionStateFlow.update { it.copy(ritualRemainingSeconds = 0) }
        }
        every { recordingSessionManager.skipRitual() } answers {
            sessionStateFlow.update { it.copy(ritualRemainingSeconds = 0) }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `1 new caution screen starts with ten seconds remaining`() = runTest {
        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        runCurrent()

        assertEquals(10, viewModel.uiState.value.remainingSeconds)
        verify { recordingSessionManager.startRitual(10) }
        assertTrue(savedStateHandle.contains(PreRecordingViewModel.KEY_RITUAL_END_TIME))
    }

    @Test
    fun `2 countdown decreases over time`() = runTest {
        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        runCurrent()
        assertEquals(10, viewModel.uiState.value.remainingSeconds)

        sessionStateFlow.update { it.copy(ritualRemainingSeconds = 5) }
        runCurrent()
        assertEquals(5, viewModel.uiState.value.remainingSeconds)

        sessionStateFlow.update { it.copy(ritualRemainingSeconds = 0) }
        runCurrent()
        assertEquals(0, viewModel.uiState.value.remainingSeconds)
    }

    @Test
    fun `3 begin recording is unavailable while remainingSeconds greater than zero`() = runTest {
        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        runCurrent()

        assertEquals(10, viewModel.uiState.value.remainingSeconds)
        assertFalse("Begin recording must not be available during countdown", viewModel.canBeginRecording())

        var proceedCalled = false
        viewModel.onBeginRecording { proceedCalled = true }
        assertFalse("onBeginRecording must not invoke proceed callback while countdown is active", proceedCalled)
    }

    @Test
    fun `4 begin recording becomes available at zero if storage permits`() = runTest {
        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        runCurrent()

        sessionStateFlow.update { it.copy(ritualRemainingSeconds = 0) }
        runCurrent()

        assertTrue("Begin recording must be available when countdown reaches 0 and storage is good", viewModel.canBeginRecording())

        var proceedCalled = false
        viewModel.onBeginRecording {
            proceedCalled = true
        }

        assertTrue("Proceed callback must be executed when remaining seconds is 0", proceedCalled)
        verify { recordingSessionManager.skipRitual() }
        assertFalse("SavedStateHandle end time must be cleared on begin recording", savedStateHandle.contains(PreRecordingViewModel.KEY_RITUAL_END_TIME))
    }

    @Test
    fun `5 critical storage still prevents recording even after countdown completes`() = runTest {
        every { storageManager.availableStorageMb } returns 400L
        every { storageManager.isStorageAvailable() } returns false

        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        runCurrent()

        sessionStateFlow.update { it.copy(ritualRemainingSeconds = 0) }
        runCurrent()

        assertFalse("Recording must not be allowed when storage is critical", viewModel.canBeginRecording())

        var proceedCalled = false
        viewModel.onBeginRecording {
            proceedCalled = true
        }
        assertFalse("Proceed callback must not be invoked when storage is critical", proceedCalled)
    }

    @Test
    fun `6 user cancellation cancels the countdown and clears state`() = runTest {
        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        runCurrent()

        viewModel.cancel()
        runCurrent()

        verify { recordingSessionManager.cancelRitual() }
        assertFalse(savedStateHandle.contains(PreRecordingViewModel.KEY_RITUAL_END_TIME))
        assertEquals(0, viewModel.uiState.value.remainingSeconds)
    }

    @Test
    fun `7 configuration changes do not reset the countdown`() = runTest {
        val now = System.currentTimeMillis()
        val originalEndTime = now + 6_000L
        savedStateHandle[PreRecordingViewModel.KEY_RITUAL_END_TIME] = originalEndTime

        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        runCurrent()

        // Should preserve existing end time, not reset to now + 10_000L
        assertEquals(originalEndTime, savedStateHandle.get<Long>(PreRecordingViewModel.KEY_RITUAL_END_TIME))
        verify(exactly = 0) { recordingSessionManager.startRitual(10) }
        verify { recordingSessionManager.startRitual(match { it in 5..7 }) }
    }

    @Test
    fun `8 savedStateHandle restoration preserves the correct remaining duration`() = runTest {
        val now = System.currentTimeMillis()
        savedStateHandle[PreRecordingViewModel.KEY_RITUAL_END_TIME] = now + 4_000L

        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        runCurrent()

        verify { recordingSessionManager.startRitual(match { it in 3..5 }) }
    }

    @Test
    fun `9 process recreation does not create duplicate countdown jobs`() = runTest {
        val now = System.currentTimeMillis()
        savedStateHandle[PreRecordingViewModel.KEY_RITUAL_END_TIME] = now + 8_000L

        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        runCurrent()

        verify(exactly = 1) { recordingSessionManager.startRitual(any()) }
    }

    @Test
    fun `10 rapid begin recording attempts cannot create duplicate recording sessions`() = runTest {
        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        runCurrent()

        sessionStateFlow.update { it.copy(ritualRemainingSeconds = 0) }
        runCurrent()

        var callCount = 0
        viewModel.onBeginRecording { callCount++ }
        viewModel.onBeginRecording { callCount++ }
        viewModel.onBeginRecording { callCount++ }

        assertEquals("Proceed callback must be called exactly once despite repeated taps", 1, callCount)
    }

    @Test
    fun `syncCountdown on resume clears ritual if time elapsed in background`() = runTest {
        val now = System.currentTimeMillis()
        savedStateHandle[PreRecordingViewModel.KEY_RITUAL_END_TIME] = now - 1_000L

        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        runCurrent()

        viewModel.syncCountdown()
        runCurrent()
        verify { recordingSessionManager.skipRitual() }
    }

    @Test
    fun `accessibility announcements trigger at start and zero without duplicates`() = runTest {
        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        runCurrent()

        var startAnnouncedCount = 0
        viewModel.triggerInitialAnnouncement { startAnnouncedCount++ }
        viewModel.triggerInitialAnnouncement { startAnnouncedCount++ }
        assertEquals("Initial announcement must trigger only once", 1, startAnnouncedCount)

        var completeAnnouncedCount = 0
        viewModel.triggerCompletionAnnouncement { completeAnnouncedCount++ }
        viewModel.triggerCompletionAnnouncement { completeAnnouncedCount++ }
        assertEquals("Completion announcement must trigger only once", 1, completeAnnouncedCount)
    }

    @Test
    fun `low storage flag is propagated when available storage is below 1GB threshold`() = runTest {
        // Less than 1024MB (1GB) but above critical minimum
        every { storageManager.availableStorageMb } returns 900L
        every { storageManager.isStorageAvailable() } returns true

        viewModel = PreRecordingViewModel(recordingSessionManager, storageManager, savedStateHandle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        runCurrent()

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
