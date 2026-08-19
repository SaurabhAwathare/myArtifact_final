package com.saurabh.artifact.ui.publish.studio

import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.ReviewState
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.domain.IdentityScout
import com.saurabh.artifact.domain.PublishArtifactUseCase
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.DraftStatus
import com.saurabh.artifact.model.PublishingResult
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
import org.junit.Assert.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PublishingStudioNavigationTest {
    private val recordingRepository = mockk<RecordingRepository>(relaxed = true)
    private val playbackCoordinator = mockk<PlaybackCoordinator>(relaxed = true)
    private val publishArtifactUseCase = mockk<PublishArtifactUseCase>(relaxed = true)
    private val identityScout = mockk<IdentityScout>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val cleanupManager = mockk<com.saurabh.artifact.audio.ArtifactCleanupManager>(relaxed = true)
    private val workManager = mockk<androidx.work.WorkManager>(relaxed = true)
    private val diagnosticLogger = mockk<com.saurabh.artifact.diagnostics.DiagnosticLogger>(relaxed = true)

    private companion object {
        private const val TEST_USER_ID = "test-user-id"
    }

    private val testDispatcher = StandardTestDispatcher()
    private val draftId = "test-draft"
    private val draftFlow = MutableStateFlow<ArtifactDraftEntity?>(null)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        val mockUser = mockk<com.google.firebase.auth.FirebaseUser> {
            every { uid } returns TEST_USER_ID
            every { displayName } returns "Test User"
            every { email } returns "test@example.com"
        }
        every { authRepository.currentUser } returns MutableStateFlow(mockUser)
        every { authRepository.currentUserId } returns TEST_USER_ID
        
        draftFlow.value = ArtifactDraftEntity(
            id = draftId,
            userId = TEST_USER_ID,
            localAudioPath = "/path/audio.wav",
            lifecycle = ArtifactLifecycle.REVIEW_REQUIRED,
            title = "Test Title",
            status = DraftStatus(),
            reviewCompleted = true,
            titleCompleted = true,
            emotionCompleted = true
        )
        
        every { recordingRepository.observeDraft(draftId) } returns draftFlow
        coEvery { recordingRepository.getDraft(draftId) } returns Result.success(draftFlow.value!!)
        
        every { playbackCoordinator.reviewProgress } returns MutableStateFlow(ReviewState())
        every { playbackCoordinator.isPlaying } returns MutableStateFlow(false)
        every { playbackCoordinator.playbackSpeed } returns MutableStateFlow(1.0f)
        every { playbackCoordinator.duration } returns flowOf(0.milliseconds)
        every { recordingRepository.observeRecoveryState(any(), any()) } returns flowOf(false)
        
        every { identityScout.detectLeaks(any(), any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `previousStep should return to APPROVAL when in PUBLISHING with error`() = runTest {
        val viewModel = createViewModel()
        viewModel.loadDraft(draftId)
        runCurrent()

        // 1. Simulate reaching APPROVAL step
        draftFlow.value = draftFlow.value!!.copy(lifecycle = ArtifactLifecycle.READY_TO_PUBLISH)
        runCurrent()
        assertEquals(StudioStep.APPROVAL, viewModel.sessionState.value.currentStep)

        // 2. Trigger Publish with failure
        coEvery { publishArtifactUseCase(any()) } returns Result.success(PublishingResult.FAILED)
        viewModel.onPublishClick()
        runCurrent()
        
        assertEquals(StudioStep.PUBLISHING, viewModel.sessionState.value.currentStep)
        assertEquals("Publishing failed to initiate. Please try again.", viewModel.sessionState.value.error)

        // 3. User clicks back
        viewModel.previousStep()
        runCurrent()

        // Verify: Returned to APPROVAL and error is cleared
        assertEquals(StudioStep.APPROVAL, viewModel.sessionState.value.currentStep)
        assertNull(viewModel.sessionState.value.error)
    }

    @Test
    fun `previousStep should stay in PUBLISHING when in PUBLISHING without error (active publish)`() = runTest {
        val viewModel = createViewModel()
        viewModel.loadDraft(draftId)
        runCurrent()

        // 1. Set state to APPROVAL
        draftFlow.value = draftFlow.value!!.copy(lifecycle = ArtifactLifecycle.READY_TO_PUBLISH)
        runCurrent()

        // 2. Mock a long-running publish
        coEvery { publishArtifactUseCase(any()) } coAnswers {
            delay(1000.milliseconds)
            Result.success(PublishingResult.UPLOAD_STARTED)
        }
        
        viewModel.onPublishClick()
        runCurrent()
        
        assertEquals(StudioStep.PUBLISHING, viewModel.sessionState.value.currentStep)
        assertNull(viewModel.sessionState.value.error)

        // 3. User clicks back during active publish
        viewModel.previousStep()
        runCurrent()

        // Verify: Navigation is blocked (stays in PUBLISHING)
        assertEquals(StudioStep.PUBLISHING, viewModel.sessionState.value.currentStep)
    }

    private fun createViewModel() = PublishingStudioViewModel(
        recordingRepository,
        cleanupManager,
        playbackCoordinator,
        publishArtifactUseCase,
        identityScout,
        authRepository,
        workManager,
        diagnosticLogger
    )
}
