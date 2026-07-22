package com.saurabh.artifact.ui.publish.studio

import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.ReviewState
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.domain.IdentityScout
import com.saurabh.artifact.domain.PublishArtifactUseCase
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.DraftStatus
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.DebugSettings
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.util.ArtifactLogger
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TitleBufferTest {
    private val recordingRepository = mockk<RecordingRepository>(relaxed = true)
    private val playbackCoordinator = mockk<PlaybackCoordinator>(relaxed = true)
    private val publishArtifactUseCase = mockk<PublishArtifactUseCase>(relaxed = true)
    private val identityScout = mockk<IdentityScout>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val cleanupManager = mockk<com.saurabh.artifact.audio.ArtifactCleanupManager>(relaxed = true)
    private val debugRepository = mockk<com.saurabh.artifact.repository.DebugRepository>(relaxed = true)
    private val workManager = mockk<androidx.work.WorkManager>(relaxed = true)
    private val diagnosticLogger = mockk<com.saurabh.artifact.diagnostics.DiagnosticLogger>(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ArtifactLogger)
        every { ArtifactLogger.d(any(), any()) } just runs
        every { ArtifactLogger.i(any(), any()) } just runs
        every { ArtifactLogger.w(any(), any(), any()) } just runs
        every { ArtifactLogger.e(any(), any(), any()) } just runs
        
        val draftId = "test-draft"
        val draftFlow = MutableStateFlow<ArtifactDraftEntity?>(
            ArtifactDraftEntity(
                id = draftId,
                localAudioPath = "/path/audio.wav",
                lifecycle = ArtifactLifecycle.REVIEW_REQUIRED,
                title = "Initial Title",
                status = DraftStatus()
            )
        )
        
        every { recordingRepository.observeDraft(draftId) } returns draftFlow
        every { playbackCoordinator.reviewProgress } returns MutableStateFlow(ReviewState())
        every { playbackCoordinator.isPlaying } returns MutableStateFlow(false)
        every { playbackCoordinator.playbackSpeed } returns MutableStateFlow(1.0f)
        every { playbackCoordinator.playbackCompletedEvent } returns MutableSharedFlow<String>()
        every { playbackCoordinator.duration } returns flowOf(0.milliseconds)
        every { playbackCoordinator.currentArtifact } returns MutableStateFlow(null)
        every { recordingRepository.observeRecoveryState(any(), any()) } returns flowOf(false)
        every { debugRepository.debugSettings } returns flowOf(DebugSettings())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `updateTitle should update local buffer immediately and Room after debounce`() = runTest {
        val viewModel = PublishingStudioViewModel(
            recordingRepository,
            cleanupManager,
            playbackCoordinator,
            publishArtifactUseCase,
            identityScout,
            authRepository,
            debugRepository,
            workManager,
            diagnosticLogger
        )

        val draftId = "test-draft"
        viewModel.loadDraft(draftId)
        
        // Trigger initial collection
        runCurrent()
        
        // Wait for sessionState to load the draft
        val state = viewModel.sessionState.filter { it.draftId == draftId }.first()
        
        // Initial state
        assertEquals("Initial Title", state.title)

        // Update title
        viewModel.updateTitle("New Title")
        runCurrent() // Process the combine emission

        // Verify: UI state shows new title IMMEDIATELY (from buffer)
        assertEquals("New Title", viewModel.sessionState.value.title)

        // Verify: Room update NOT called yet (before debounce)
        coVerify(exactly = 0) { recordingRepository.updateDraftMetadata(draftId, "New Title", any()) }

        // Wait for debounce (500ms)
        advanceTimeBy(600)
        runCurrent() // Trigger the debounced job

        // Verify: Room update CALLED
        coVerify(exactly = 1) { recordingRepository.updateDraftMetadata(draftId, "New Title", any()) }
    }
}
