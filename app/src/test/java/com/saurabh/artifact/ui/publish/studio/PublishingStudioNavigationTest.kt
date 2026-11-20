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
import androidx.compose.ui.test.junit4.createComposeRule
import com.saurabh.artifact.model.SyncStatus
import com.saurabh.artifact.ui.theme.ArtifactTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class PublishingStudioNavigationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

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
    fun `performPublish success sets isSubmitted to true and isSuccess to false without locking in full screen publishing step`() = runTest {
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
        assertTrue("Expected isSubmitted to be true after publish initiation", lastState.isSubmitted)
        assertFalse("Expected isSuccess to be false after publish initiation", lastState.isSuccess)
        assertFalse("Expected isPublishing to be false after completion", lastState.isPublishing)
        assertNull(lastState.error)

        collectJob.cancel()
    }

    @Test
    fun `UPLOAD_STARTED does not produce publication success`() = runTest {
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

        coEvery { publishArtifactUseCase(any()) } returns Result.success(PublishingResult.UPLOAD_STARTED)
        viewModel.onPublishClick()
        runCurrent()
        advanceUntilIdle()

        val lastState = states.last()
        assertTrue("Expected isSubmitted to be true after UPLOAD_STARTED", lastState.isSubmitted)
        assertFalse("Expected isSuccess to be false after UPLOAD_STARTED", lastState.isSuccess)
        assertFalse("Expected isPublishing to be false after handoff", lastState.isPublishing)
        assertNull(lastState.error)

        collectJob.cancel()
    }

    @Test
    fun `QUEUED_OFFLINE does not produce publication success`() = runTest {
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

        coEvery { publishArtifactUseCase(any()) } returns Result.success(PublishingResult.QUEUED_OFFLINE)
        viewModel.onPublishClick()
        runCurrent()
        advanceUntilIdle()

        val lastState = states.last()
        assertTrue("Expected isSubmitted to be true after QUEUED_OFFLINE", lastState.isSubmitted)
        assertTrue("Expected isQueuedOffline to be true after QUEUED_OFFLINE", lastState.isQueuedOffline)
        assertFalse("Expected isSuccess to be false after QUEUED_OFFLINE", lastState.isSuccess)
        assertFalse("Expected isPublishing to be false after handoff", lastState.isPublishing)
        assertNull(lastState.error)

        collectJob.cancel()
    }

    @Test
    fun `successful submission handoff can navigate Home`() = runTest {
        val viewModel = createViewModel()
        var finishCalled = 0

        composeTestRule.setContent {
            ArtifactTheme {
                PublishingStudioScreen(
                    draftId = draftId,
                    onFinish = { finishCalled++ },
                    onCancel = {},
                    onSecurityRequired = {},
                    viewModel = viewModel
                )
            }
        }

        // Set state to APPROVAL
        draftFlow.value = draftFlow.value!!.copy(
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            emotion = Emotion.CALM,
            reviewCompleted = true,
            titleCompleted = true,
            emotionCompleted = true
        )
        composeTestRule.waitForIdle()

        // Initiation returns UPLOAD_STARTED
        coEvery { publishArtifactUseCase(any()) } returns Result.success(PublishingResult.UPLOAD_STARTED)
        viewModel.onPublishClick()
        composeTestRule.waitForIdle()

        assertEquals("onFinish must be called once upon successful submission handoff", 1, finishCalled)
        assertTrue("ViewModel state must report isSubmitted", viewModel.sessionState.value.isSubmitted)
        assertFalse("ViewModel state must NOT report isSuccess", viewModel.sessionState.value.isSuccess)
    }

    @Test
    fun `failed initiation does not navigate Home as a successful submission`() = runTest {
        val viewModel = createViewModel()
        var finishCalled = 0

        composeTestRule.setContent {
            ArtifactTheme {
                PublishingStudioScreen(
                    draftId = draftId,
                    onFinish = { finishCalled++ },
                    onCancel = {},
                    onSecurityRequired = {},
                    viewModel = viewModel
                )
            }
        }

        draftFlow.value = draftFlow.value!!.copy(
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            emotion = Emotion.CALM,
            reviewCompleted = true,
            titleCompleted = true,
            emotionCompleted = true
        )
        composeTestRule.waitForIdle()

        coEvery { publishArtifactUseCase(any()) } returns Result.success(PublishingResult.FAILED)
        viewModel.onPublishClick()
        composeTestRule.waitForIdle()

        assertEquals("onFinish must NOT be called on failed initiation", 0, finishCalled)
        assertFalse("isSubmitted must be false on failure", viewModel.sessionState.value.isSubmitted)
        assertFalse("isSuccess must be false on failure", viewModel.sessionState.value.isSuccess)
    }

    @Test
    fun `actual persisted publication state produces isSuccess`() = runTest {
        val viewModel = createViewModel()
        val states = mutableListOf<StudioSessionState>()
        val collectJob = launch(UnconfinedTestDispatcher()) {
            viewModel.sessionState.toList(states)
        }
        viewModel.loadDraft(draftId)
        runCurrent()

        // Initially READY_TO_PUBLISH with Uploading sync status
        draftFlow.value = draftFlow.value!!.copy(
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            emotion = Emotion.CALM,
            status = DraftStatus(publication = SyncStatus.Uploading)
        )
        runCurrent()
        assertFalse("In-progress publishing draft must not produce isSuccess", states.last().isSuccess)

        // Authoritative persisted state updates to PUBLISHED and Synced in Room
        draftFlow.value = draftFlow.value!!.copy(
            lifecycle = ArtifactLifecycle.PUBLISHED,
            status = DraftStatus(publication = SyncStatus.Synced),
            remoteArtifactId = "remote-artifact-999"
        )
        runCurrent()

        assertTrue("Persisted PUBLISHED + Synced draft must produce isSuccess == true", states.last().isSuccess)

        collectJob.cancel()
    }

    @Test
    fun `repeated state emissions do not cause duplicate navigation`() = runTest {
        val viewModel = createViewModel()
        var finishCalled = 0

        composeTestRule.setContent {
            ArtifactTheme {
                PublishingStudioScreen(
                    draftId = draftId,
                    onFinish = { finishCalled++ },
                    onCancel = {},
                    onSecurityRequired = {},
                    viewModel = viewModel
                )
            }
        }

        draftFlow.value = draftFlow.value!!.copy(
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            emotion = Emotion.CALM,
            reviewCompleted = true,
            titleCompleted = true,
            emotionCompleted = true
        )
        composeTestRule.waitForIdle()

        coEvery { publishArtifactUseCase(any()) } returns Result.success(PublishingResult.UPLOAD_STARTED)
        viewModel.onPublishClick()
        composeTestRule.waitForIdle()

        assertEquals("Initial handoff calls onFinish once", 1, finishCalled)

        // Multiple subsequent state emissions while isSubmitted is still true
        draftFlow.value = draftFlow.value!!.copy(title = "Updated Title 1")
        composeTestRule.waitForIdle()
        draftFlow.value = draftFlow.value!!.copy(title = "Updated Title 2")
        composeTestRule.waitForIdle()
        draftFlow.value = draftFlow.value!!.copy(reviewProgress = 0.99f)
        composeTestRule.waitForIdle()

        assertEquals("Subsequent emissions must not trigger duplicate navigation", 1, finishCalled)
    }

    @Test
    fun `existing ALREADY_IN_PROGRESS behavior remains correct`() = runTest {
        val viewModel = createViewModel()
        val states = mutableListOf<StudioSessionState>()
        val collectJob = launch(UnconfinedTestDispatcher()) {
            viewModel.sessionState.toList(states)
        }
        viewModel.loadDraft(draftId)
        runCurrent()

        // Case A: Draft is already being published in background
        draftFlow.value = draftFlow.value!!.copy(
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            emotion = Emotion.CALM,
            reviewCompleted = true,
            titleCompleted = true,
            emotionCompleted = true
        )
        runCurrent()

        coEvery { publishArtifactUseCase(any()) } returns Result.success(PublishingResult.ALREADY_IN_PROGRESS)
        viewModel.onPublishClick()
        runCurrent()
        advanceUntilIdle()

        val inProgressState = states.last()
        assertTrue("Expected isSubmitted to be true for handoff", inProgressState.isSubmitted)
        assertFalse("Expected isSuccess to be false while still uploading", inProgressState.isSuccess)
        assertFalse("Expected isPublishing to be false", inProgressState.isPublishing)
        assertNull(inProgressState.error)

        // Case B: Persisted state reaches PUBLISHED
        draftFlow.value = draftFlow.value!!.copy(
            lifecycle = ArtifactLifecycle.PUBLISHED,
            status = DraftStatus(publication = SyncStatus.Synced)
        )
        runCurrent()

        assertTrue("Expected isSuccess to be true once draft is PUBLISHED", states.last().isSuccess)

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
        assertTrue("Expected isSubmitted to be true", states.last().isSubmitted)
        assertFalse("Expected isSuccess to be false upon UPLOAD_STARTED", states.last().isSuccess)

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
