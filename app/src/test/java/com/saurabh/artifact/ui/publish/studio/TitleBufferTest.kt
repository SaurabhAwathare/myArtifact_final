package com.saurabh.artifact.ui.publish.studio

import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.ReviewState
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.domain.IdentityScout
import com.saurabh.artifact.domain.PublishArtifactUseCase
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.DraftStatus
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.RecordingRepository
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
    private val databaseEncryptionManager = mockk<com.saurabh.artifact.security.DatabaseEncryptionManager>(relaxed = true)
    private val workManager = mockk<androidx.work.WorkManager>(relaxed = true)
    private val diagnosticLogger = mockk<com.saurabh.artifact.diagnostics.DiagnosticLogger>(relaxed = true)

    private companion object {
        private const val TEST_USER_ID = "test-user-id"
    }

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(com.saurabh.artifact.diagnostics.ArtifactLogger)
        every { com.saurabh.artifact.diagnostics.ArtifactLogger.d(any(), any(), any()) } just runs
        every { com.saurabh.artifact.diagnostics.ArtifactLogger.i(any(), any(), any()) } just runs
        every { com.saurabh.artifact.diagnostics.ArtifactLogger.w(any(), any(), any(), any()) } just runs
        every { com.saurabh.artifact.diagnostics.ArtifactLogger.e(any(), any(), any(), any()) } just runs

        val mockUser = mockk<com.google.firebase.auth.FirebaseUser> {
            every { uid } returns TEST_USER_ID
        }
        every { authRepository.currentUser } returns MutableStateFlow(mockUser)
        every { authRepository.currentUserId } returns TEST_USER_ID
        
        val draftId = "test-draft"
        val draftFlow = MutableStateFlow<ArtifactDraftEntity?>(
            ArtifactDraftEntity(
                id = draftId,
                userId = TEST_USER_ID,
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
        every { databaseEncryptionManager.isRecoverySetup } returns MutableStateFlow(true)
        coEvery { recordingRepository.updateDraftMetadata(any(), any(), any<List<Emotion>>()) } answers {
            val titleArg = secondArg<String?>()
            draftFlow.value = draftFlow.value?.copy(title = titleArg)
            Result.success(Unit)
        }
        coEvery { recordingRepository.updateStudioState(any(), any(), any(), any()) } returns Result.success(Unit)
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
            databaseEncryptionManager,
            workManager,
            diagnosticLogger
        )

        backgroundScope.launch { viewModel.sessionState.collect() }

        val draftId = "test-draft"
        viewModel.loadDraft(draftId)
        advanceUntilIdle()
        
        // Initial state
        assertEquals("Initial Title", viewModel.sessionState.value.title)

        // Update title
        viewModel.updateTitle("New Title")
        runCurrent()

        // Verify: UI state shows new title IMMEDIATELY (from buffer)
        assertEquals("New Title", viewModel.sessionState.value.title)

        // Verify: Room update NOT called yet (before debounce)
        coVerify(exactly = 0) { recordingRepository.updateDraftMetadata(draftId, "New Title", any<List<Emotion>>()) }

        // Wait for debounce (500ms)
        advanceTimeBy(600)
        runCurrent()

        // Verify: Room update CALLED
        coVerify(exactly = 1) { recordingRepository.updateDraftMetadata(draftId, "New Title", any<List<Emotion>>()) }
    }

    @Test
    fun `updateTitle should enforce 70 character limit`() = runTest {
        val viewModel = PublishingStudioViewModel(
            recordingRepository,
            cleanupManager,
            playbackCoordinator,
            publishArtifactUseCase,
            identityScout,
            authRepository,
            databaseEncryptionManager,
            workManager,
            diagnosticLogger
        )

        backgroundScope.launch { viewModel.sessionState.collect() }

        val draftId = "test-draft"
        viewModel.loadDraft(draftId)
        advanceUntilIdle()

        val longTitle = "A".repeat(100)
        val expectedTitle = "A".repeat(70)

        viewModel.updateTitle(longTitle)
        runCurrent()

        // Verify local buffer is truncated
        assertEquals(expectedTitle, viewModel.sessionState.value.title)

        advanceTimeBy(600)
        runCurrent()

        // Verify repo update uses truncated title
        coVerify(exactly = 1) { recordingRepository.updateDraftMetadata(draftId, expectedTitle, any<List<Emotion>>()) }
    }
}
