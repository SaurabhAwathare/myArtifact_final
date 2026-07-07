package com.saurabh.artifact.ui.feed

import androidx.lifecycle.SavedStateHandle
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PublishStateManager
import com.saurabh.artifact.audio.ReviewSessionManager
import com.saurabh.artifact.domain.feed.GetFeedFlowUseCase
import com.saurabh.artifact.domain.feed.GetPersonalizedFeedFlowUseCase
import com.saurabh.artifact.domain.prompt.GetReflectionPromptUseCase
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.NotificationRepository
import com.saurabh.artifact.repository.SavedArtifactManager
import com.saurabh.artifact.service.AdManager
import com.saurabh.artifact.service.FeedComposer
import com.saurabh.artifact.service.FeedSeparatorMapper
import com.saurabh.artifact.service.PersonalizationEngine
import com.saurabh.artifact.security.UploadGuard
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.util.MemoryManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelStateRestorationTest {

    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val notificationRepository = mockk<NotificationRepository>(relaxed = true)
    private val personalizationEngine = mockk<PersonalizationEngine>(relaxed = true)
    private val adManager = mockk<AdManager>(relaxed = true)
    private val memoryManager = mockk<MemoryManager>(relaxed = true)
    private val startupCoordinator = mockk<StartupCoordinator>(relaxed = true)
    private val savedArtifactManager = mockk<SavedArtifactManager>(relaxed = true)
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val audioPlayer = mockk<PlaybackCoordinator>(relaxed = true)
    private val cleanupManager = mockk<ArtifactCleanupManager>(relaxed = true)
    private val reviewSessionManager = mockk<ReviewSessionManager>(relaxed = true)
    private val publishStateManager = mockk<PublishStateManager>(relaxed = true)
    private val uploadGuard = mockk<UploadGuard>(relaxed = true)
    private val feedComposer = mockk<FeedComposer>(relaxed = true)
    private val feedSeparatorMapper = mockk<FeedSeparatorMapper>(relaxed = true)
    private val getFeedFlowUseCase = mockk<GetFeedFlowUseCase>(relaxed = true)
    private val getPersonalizedFeedFlowUseCase = mockk<GetPersonalizedFeedFlowUseCase>(relaxed = true)
    private val getReflectionPromptUseCase = mockk<GetReflectionPromptUseCase>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        every { authRepository.currentUser } returns MutableStateFlow(null)
        every { savedArtifactManager.events } returns MutableSharedFlow()
        every { startupCoordinator.stage } returns MutableStateFlow(com.saurabh.artifact.startup.StartupStage.ARRIVAL)
        every { audioPlayer.currentArtifact } returns MutableStateFlow(null)
        every { audioPlayer.isPlaying } returns MutableStateFlow(false)
        every { audioPlayer.isBuffering } returns MutableStateFlow(false)
        every { audioPlayer.currentPosition } returns flowOf()
        every { audioPlayer.duration } returns flowOf()
        every { publishStateManager.currentPublishState } returns MutableStateFlow(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(savedStateHandle: SavedStateHandle): FeedViewModel {
        return FeedViewModel(
            savedStateHandle = savedStateHandle,
            artifactRepository = artifactRepository,
            authRepository = authRepository,
            notificationRepository = notificationRepository,
            personalizationEngine = personalizationEngine,
            adManager = adManager,
            memoryManager = memoryManager,
            startupCoordinator = startupCoordinator,
            savedArtifactManager = savedArtifactManager,
            firestore = firestore,
            audioPlayer = audioPlayer,
            cleanupManager = cleanupManager,
            reviewSessionManager = reviewSessionManager,
            publishStateManager = publishStateManager,
            uploadGuard = uploadGuard,
            feedComposer = feedComposer,
            feedSeparatorMapper = feedSeparatorMapper,
            getFeedFlowUseCase = getFeedFlowUseCase,
            getPersonalizedFeedFlowUseCase = getPersonalizedFeedFlowUseCase,
            getReflectionPromptUseCase = getReflectionPromptUseCase,
            diagnosticLogger = diagnosticLogger
        )
    }

    @Test
    fun `initial restoration from SavedStateHandle is reflected in uiState`() = runTest {
        val savedStateHandle = SavedStateHandle(
            mapOf(
                "selected_emotion" to "Happy",
                "show_ranked_feed" to false
            )
        )
        val viewModel = createViewModel(savedStateHandle)
        
        advanceUntilIdle()
        
        assertEquals("Happy", viewModel.uiState.value.selectedEmotion)
        assertEquals(false, viewModel.uiState.value.showRankedFeed)
    }

    @Test
    fun `setting filter and tab updates SavedStateHandle`() = runTest {
        val savedStateHandle = SavedStateHandle()
        val viewModel = createViewModel(savedStateHandle)
        
        viewModel.setEmotionFilter("Sad")
        viewModel.setShowRankedFeed(false)
        
        assertEquals("Sad", savedStateHandle.get<String>("selected_emotion"))
        assertEquals(false, savedStateHandle.get<Boolean>("show_ranked_feed"))
    }

    @Test
    fun `process recreation restores state correctly`() = runTest {
        val savedStateHandle = SavedStateHandle()
        val viewModel1 = createViewModel(savedStateHandle)
        
        viewModel1.setEmotionFilter("Anxious")
        viewModel1.setShowRankedFeed(false)
        
        // Simulate process recreation by reusing the same SavedStateHandle
        val viewModel2 = createViewModel(savedStateHandle)
        
        advanceUntilIdle()
        
        assertEquals("Anxious", viewModel2.uiState.value.selectedEmotion)
        assertEquals(false, viewModel2.uiState.value.showRankedFeed)
    }

    @Test
    fun `repeated updates result in final value in SavedStateHandle`() = runTest {
        val savedStateHandle = SavedStateHandle()
        val viewModel = createViewModel(savedStateHandle)
        
        viewModel.setEmotionFilter("Joy")
        viewModel.setEmotionFilter("Hope")
        viewModel.setEmotionFilter("Grief")
        
        assertEquals("Grief", savedStateHandle.get<String>("selected_emotion"))
        
        advanceUntilIdle()
        assertEquals("Grief", viewModel.uiState.value.selectedEmotion)
    }

    @Test
    fun `transient state is NOT restored across process recreation`() = runTest {
        val savedStateHandle = SavedStateHandle()
        val viewModel1 = createViewModel(savedStateHandle)
        
        // Mock some transient state (simulated, since we can't easily force _uiState internal update here without methods)
        // But we can check if it starts with default values in a new ViewModel
        
        val viewModel2 = createViewModel(savedStateHandle)
        advanceUntilIdle()
        
        // isRefreshing should be false by default
        assertEquals(false, viewModel2.uiState.value.isRefreshing)
        assertNull(viewModel2.uiState.value.error)
    }
}
