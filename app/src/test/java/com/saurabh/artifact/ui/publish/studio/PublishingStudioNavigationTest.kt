package com.saurabh.artifact.ui.publish.studio

import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseUser
import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.ReviewState
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.domain.IdentityScout
import com.saurabh.artifact.domain.PublishArtifactUseCase
import com.saurabh.artifact.domain.PublishingOrchestrator
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.DraftStatus
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.model.PublishingResult
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.security.DatabaseEncryptionManager
import com.saurabh.artifact.util.OnboardingManager
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    private val cleanupManager = mockk<ArtifactCleanupManager>(relaxed = true)
    private val databaseEncryptionManager = mockk<DatabaseEncryptionManager>(relaxed = true)
    private val onboardingManager = mockk<OnboardingManager>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private val publishingOrchestrator = mockk<PublishingOrchestrator>(relaxed = true)

    private companion object {
        private const val TEST_USER_ID = "test-user-id"
    }

    private val testDispatcher = UnconfinedTestDispatcher()
    private val draftId = "test-draft"
    private val draftFlow = MutableStateFlow<ArtifactDraftEntity?>(null)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        val mockUser = mockk<FirebaseUser>(relaxed = true) {
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
        
        every { recordingRepository.observeDraft(any()) } returns draftFlow
        coEvery { recordingRepository.getDraft(any()) } answers { Result.success(draftFlow.value!!) }
        
        every { playbackCoordinator.reviewProgress } returns MutableStateFlow(ReviewState())
        every { playbackCoordinator.isPlaying } returns MutableStateFlow(false)
        every { playbackCoordinator.playbackSpeed } returns MutableStateFlow(1.0f)
        every { playbackCoordinator.duration } returns flowOf(0.milliseconds)
        every { recordingRepository.observeRecoveryState(any(), any()) } returns flowOf(false)
        every { databaseEncryptionManager.isRecoverySetup } returns flowOf(true)
        every { onboardingManager.isMnemonicSaved(any()) } returns flowOf(true)
        every { playbackCoordinator.currentPosition } returns flowOf(0.milliseconds)
        
        every { identityScout.detectLeaks(any(), any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `performPublish success sets isSuccess to true without locking in full screen publishing step`() = runTest {
        val viewModel = createViewModel()
        val states = mutableListOf<StudioSessionState>()
        val collectJob = launch(UnconfinedTestDispatcher()) {
            viewModel.sessionState.toList(states)
        }
        
        viewModel.loadDraft(draftId)
        runCurrent()

        // 1. Simulate reaching APPROVAL step
        draftFlow.value = draftFlow.value!!.copy(
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            emotion = Emotion.CALM,
            reviewCompleted = true,
            titleCompleted = true,
            emotionCompleted = true
        )
        runCurrent()
        assertEquals(StudioStep.APPROVAL, states.last().currentStep)

        // 2. Trigger Publish with UPLOAD_STARTED result
        coEvery { publishArtifactUseCase(any()) } returns Result.success(PublishingResult.UPLOAD_STARTED)
        viewModel.onPublishClick()
        runCurrent()
        advanceUntilIdle()
        
        val lastState = states.last()
        assertTrue("Expected isSuccess to be true after publish initiation", lastState.isSuccess)
        assertFalse("Expected isPublishing to be false after completion", lastState.isPublishing)
        assertNull(lastState.error)

        collectJob.cancel()
    }

    @Test
    fun `performPublish failure sets error and remains on APPROVAL step`() = runTest {
        val viewModel = createViewModel()
        val states = mutableListOf<StudioSessionState>()
        val collectJob = launch(UnconfinedTestDispatcher()) {
            viewModel.sessionState.toList(states)
        }
        
        viewModel.loadDraft(draftId)
        runCurrent()

        // 1. Set state to APPROVAL
        draftFlow.value = draftFlow.value!!.copy(
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            emotion = Emotion.CALM,
            reviewCompleted = true,
            titleCompleted = true,
            emotionCompleted = true
        )
        runCurrent()
        assertEquals(StudioStep.APPROVAL, states.last().currentStep)

        // 2. Mock a publish failure
        coEvery { publishArtifactUseCase(any()) } returns Result.success(PublishingResult.FAILED)
        viewModel.onPublishClick()
        runCurrent()
        advanceUntilIdle()
        
        val lastState = states.last()
        assertEquals(StudioStep.APPROVAL, lastState.currentStep)
        assertEquals("Publishing failed to initiate. Please try again.", lastState.error)
        assertFalse(lastState.isSuccess)

        collectJob.cancel()
    }

    @Test
    fun `performPublish should propagate specific validation error message to UI`() = runTest {
        val viewModel = createViewModel()
        val states = mutableListOf<StudioSessionState>()
        val collectJob = launch(UnconfinedTestDispatcher()) {
            viewModel.sessionState.toList(states)
        }
        
        viewModel.loadDraft(draftId)
        runCurrent()

        // Set state to APPROVAL
        draftFlow.value = draftFlow.value!!.copy(
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            emotion = Emotion.CALM,
            reviewCompleted = true,
            titleCompleted = true,
            emotionCompleted = true
        )
        runCurrent()
        assertEquals(StudioStep.APPROVAL, states.last().currentStep)

        val specificError = "Artifacts must be at least 3 seconds long."
        coEvery { publishArtifactUseCase(any()) } returns Result.failure(AppError.InvalidInput(specificError))
        
        viewModel.onPublishClick()
        runCurrent()
        
        assertEquals("Invalid input: $specificError", states.last().error)
        assertEquals(StudioStep.APPROVAL, states.last().currentStep)
        assertFalse(states.last().isSuccess)
        
        collectJob.cancel()
    }

    @Test
    fun `opening a PROCESSING draft triggers recovery via ensureProcessingActive`() = runTest {
        val viewModel = createViewModel()
        val states = mutableListOf<StudioSessionState>()
        val collectJob = launch(UnconfinedTestDispatcher()) {
            viewModel.sessionState.toList(states)
        }

        val processingDraftId = "processing-draft"
        val processingDraft = ArtifactDraftEntity(
            id = processingDraftId,
            userId = TEST_USER_ID,
            localAudioPath = "/path/audio.wav",
            lifecycle = ArtifactLifecycle.PROCESSING,
            status = DraftStatus()
        )
        every { recordingRepository.observeDraft(processingDraftId) } returns MutableStateFlow(processingDraft)

        viewModel.loadDraft(processingDraftId)
        runCurrent()

        coVerify(exactly = 1) { publishingOrchestrator.ensureProcessingActive(processingDraftId) }

        collectJob.cancel()
    }

    @Test
    fun `opening a REVIEW_REQUIRED draft does not trigger processing`() = runTest {
        val viewModel = createViewModel()
        val states = mutableListOf<StudioSessionState>()
        val collectJob = launch(UnconfinedTestDispatcher()) {
            viewModel.sessionState.toList(states)
        }

        val reviewDraftId = "review-draft"
        val reviewDraft = ArtifactDraftEntity(
            id = reviewDraftId,
            userId = TEST_USER_ID,
            localAudioPath = "/path/audio.wav",
            lifecycle = ArtifactLifecycle.REVIEW_REQUIRED,
            status = DraftStatus()
        )
        every { recordingRepository.observeDraft(reviewDraftId) } returns MutableStateFlow(reviewDraft)

        viewModel.loadDraft(reviewDraftId)
        runCurrent()

        coVerify(exactly = 0) { publishingOrchestrator.ensureProcessingActive(reviewDraftId) }

        collectJob.cancel()
    }

    @Test
    fun `performPublish redirects to SECURITY_REQUIRED when account mnemonic is not saved despite device recovery wrapper`() = runTest {
        every { databaseEncryptionManager.isRecoverySetup } returns flowOf(true)
        every { onboardingManager.isMnemonicSaved(any()) } returns flowOf(false)

        val viewModel = createViewModel()
        val states = mutableListOf<StudioSessionState>()
        val collectJob = launch(UnconfinedTestDispatcher()) {
            viewModel.sessionState.toList(states)
        }

        viewModel.loadDraft(draftId)
        runCurrent()

        draftFlow.value = draftFlow.value!!.copy(
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            emotion = Emotion.CALM,
            reviewCompleted = true,
            titleCompleted = true,
            emotionCompleted = true
        )
        runCurrent()

        viewModel.onPublishClick()
        runCurrent()

        val lastState = states.last()
        assertEquals(StudioStep.SECURITY_REQUIRED, lastState.currentStep)
        coVerify(exactly = 0) { publishArtifactUseCase(any()) }

        collectJob.cancel()
    }

    @Test
    fun `playback position and listening coverage update independently`() = runTest {
        val positionFlow = MutableStateFlow(0.milliseconds)
        val reviewProgressFlow = MutableStateFlow(ReviewState(coveragePercent = 0.5f))
        every { playbackCoordinator.currentPosition } returns positionFlow
        every { playbackCoordinator.reviewProgress } returns reviewProgressFlow

        val viewModel = createViewModel()
        val states = mutableListOf<StudioSessionState>()
        val collectJob = launch(UnconfinedTestDispatcher()) {
            viewModel.sessionState.toList(states)
        }

        draftFlow.value = draftFlow.value!!.copy(
            durationMs = 10_000L,
            lifecycle = ArtifactLifecycle.REVIEW_REQUIRED
        )
        viewModel.loadDraft(draftId)
        runCurrent()

        // Initially at position 0, coverage 50%
        var state = states.last()
        assertEquals(0L, state.currentPosition)
        assertEquals(0f, state.playbackProgress, 0.001f)
        assertEquals(0.5f, state.coveragePercent, 0.001f)

        // Advance playback position to 2500ms (25% of duration)
        positionFlow.value = 2500.milliseconds
        runCurrent()

        state = states.last()
        assertEquals(2500L, state.currentPosition)
        assertEquals(0.25f, state.playbackProgress, 0.001f)
        // Coverage remains 50%
        assertEquals(0.5f, state.coveragePercent, 0.001f)

        collectJob.cancel()
    }

    @Test
    fun `rewinding playback position does not erase listening coverage`() = runTest {
        val positionFlow = MutableStateFlow(8000.milliseconds)
        val reviewProgressFlow = MutableStateFlow(ReviewState(coveragePercent = 0.8f))
        every { playbackCoordinator.currentPosition } returns positionFlow
        every { playbackCoordinator.reviewProgress } returns reviewProgressFlow

        val viewModel = createViewModel()
        val states = mutableListOf<StudioSessionState>()
        val collectJob = launch(UnconfinedTestDispatcher()) {
            viewModel.sessionState.toList(states)
        }

        draftFlow.value = draftFlow.value!!.copy(
            durationMs = 10_000L,
            lifecycle = ArtifactLifecycle.REVIEW_REQUIRED
        )
        viewModel.loadDraft(draftId)
        runCurrent()

        var state = states.last()
        assertEquals(8000L, state.currentPosition)
        assertEquals(0.8f, state.playbackProgress, 0.001f)
        assertEquals(0.8f, state.coveragePercent, 0.001f)

        // User rewinds to 2000ms
        positionFlow.value = 2000.milliseconds
        runCurrent()

        state = states.last()
        assertEquals(2000L, state.currentPosition)
        assertEquals(0.2f, state.playbackProgress, 0.001f)
        // Listening coverage MUST NOT be reduced because of rewind
        assertEquals(0.8f, state.coveragePercent, 0.001f)

        collectJob.cancel()
    }

    @Test
    fun `repeated onPublishClick calls while publishing is in progress do not launch concurrent submissions`() = runTest {
        val viewModel = createViewModel()
        val states = mutableListOf<StudioSessionState>()
        val collectJob = launch(UnconfinedTestDispatcher()) {
            viewModel.sessionState.toList(states)
        }

        viewModel.loadDraft(draftId)
        runCurrent()

        draftFlow.value = draftFlow.value!!.copy(
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            emotion = Emotion.CALM,
            reviewCompleted = true,
            titleCompleted = true,
            emotionCompleted = true
        )
        runCurrent()

        val publishStartedCompletable = CompletableDeferred<Unit>()
        coEvery { publishArtifactUseCase(any()) } coAnswers {
            publishStartedCompletable.await()
            Result.success(PublishingResult.UPLOAD_STARTED)
        }

        // First click initiates publishing
        viewModel.onPublishClick()
        runCurrent()

        assertTrue("Expected isPublishing to be true", states.last().isPublishing)

        // Rapid duplicate clicks while isPublishing is true
        viewModel.onPublishClick()
        viewModel.onPublishClick()
        runCurrent()

        // Release the first publish
        publishStartedCompletable.complete(Unit)
        runCurrent()
        advanceUntilIdle()

        // Exactly one submission was launched
        coVerify(exactly = 1) { publishArtifactUseCase(draftId) }
        assertFalse("Expected isPublishing to be false after completion", states.last().isPublishing)
        assertTrue("Expected isSuccess to be true", states.last().isSuccess)

        collectJob.cancel()
    }

    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)

    private fun createViewModel() = PublishingStudioViewModel(
        recordingRepository,
        cleanupManager,
        playbackCoordinator,
        publishArtifactUseCase,
        identityScout,
        authRepository,
        databaseEncryptionManager,
        workManager,
        diagnosticLogger,
        publishingOrchestrator,
        artifactRepository,
        userRepository,
        onboardingManager
    )
}
