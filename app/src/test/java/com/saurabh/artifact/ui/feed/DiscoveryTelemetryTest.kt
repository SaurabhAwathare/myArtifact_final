package com.saurabh.artifact.ui.feed

import androidx.lifecycle.SavedStateHandle
import com.google.firebase.Timestamp
import com.saurabh.artifact.audio.validation.ReviewProgress
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.*
import com.saurabh.artifact.service.AdManager
import com.saurabh.artifact.service.FeedComposer
import com.saurabh.artifact.service.FeedSeparatorMapper
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.util.MemoryManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryTelemetryTest {

    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private val feedComposer = mockk<FeedComposer>(relaxed = true)
    private val adManager = mockk<AdManager>(relaxed = true)
    private val memoryManager = mockk<MemoryManager>(relaxed = true)
    private val onboardingManager = mockk<com.saurabh.artifact.util.OnboardingManager>(relaxed = true)
    private val startupCoordinator = mockk<StartupCoordinator>(relaxed = true)
    private val firestore = mockk<com.google.firebase.firestore.FirebaseFirestore>(relaxed = true)
    private val audioPlayer = mockk<com.saurabh.artifact.audio.PlaybackCoordinator>(relaxed = true)
    
    private val testDispatcher = StandardTestDispatcher()
    private val currentUserId = "test-user"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { authRepository.currentUser } returns MutableStateFlow(mockk { every { uid } returns currentUserId })
        every { startupCoordinator.stage } returns MutableStateFlow(com.saurabh.artifact.startup.StartupStage.STABLE)
        every { onboardingManager.isMnemonicSaved } returns MutableStateFlow(true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel() = FeedViewModel(
        savedStateHandle = SavedStateHandle(),
        artifactRepository = artifactRepository,
        artifactEngagementRepository = mockk(relaxed = true),
        authRepository = authRepository,
        notificationRepository = mockk(relaxed = true),
        communityRepository = mockk(relaxed = true),
        personalizationEngine = mockk(relaxed = true),
        adManager = adManager,
        memoryManager = memoryManager,
        onboardingManager = onboardingManager,
        startupCoordinator = startupCoordinator,
        savedArtifactManager = mockk(relaxed = true),
        firestore = firestore,
        audioPlayer = audioPlayer,
        publishStateManager = mockk(relaxed = true),
        uploadGuard = mockk(relaxed = true),
        feedComposer = feedComposer,
        feedSeparatorMapper = mockk(relaxed = true),
        getFeedFlowUseCase = mockk(relaxed = true),
        getPersonalizedFeedFlowUseCase = mockk(relaxed = true),
        getReflectionPromptUseCase = mockk(relaxed = true),
        diagnosticLogger = diagnosticLogger
    )

    @Test
    fun `loadRankedFeed logs age distribution for top 5 artifacts`() = runTest {
        val now = Date()
        val oneHourAgo = Date(now.time - 3600000)
        
        val artifacts = (1..5).map { i ->
            FeedArtifact(
                artifact = Artifact(id = "art-$i", createdAt = Timestamp(oneHourAgo)),
                reason = FeedRecommendationReason.DISCOVERY
            )
        }
        
        coEvery { feedComposer.composeFeed(currentUserId) } returns artifacts
        
        val viewModel = createViewModel()
        viewModel.loadRankedFeed()
        advanceUntilIdle()
        
        verify { diagnosticLogger.info(DiagnosticCategory.FEED, "DISCOVERY_AGE_DISTRIBUTION", match { it["avgAgeHours"] == 1.0 }) }
    }

    @Test
    fun `loadRankedFeed logs churn rate relative to previous refresh`() = runTest {
        val page1 = (1..5).map { i ->
            FeedArtifact(artifact = Artifact(id = "art-$i"), reason = FeedRecommendationReason.DISCOVERY)
        }
        val page2 = (3..7).map { i -> // art-3, art-4, art-5 are same. art-6, art-7 are new. (2/5 = 40% churn)
            FeedArtifact(artifact = Artifact(id = "art-$i"), reason = FeedRecommendationReason.DISCOVERY)
        }
        
        coEvery { feedComposer.composeFeed(currentUserId) } returnsMany listOf(page1, page2)
        
        val viewModel = createViewModel()
        
        // First load
        viewModel.loadRankedFeed()
        advanceUntilIdle()
        
        // Second load (Churn log expected)
        viewModel.loadRankedFeed()
        advanceUntilIdle()
        
        verify { diagnosticLogger.info(DiagnosticCategory.FEED, "DISCOVERY_CHURN_RATE", match { it["churnRate"] == 0.4f }) }
    }

    @Test
    fun `recordImpression logs discovery impressions correctly`() = runTest {
        val viewModel = createViewModel()
        
        viewModel.recordImpression("art-123", FeedRecommendationReason.EMOTIONAL_RESONANCE)
        
        verify { diagnosticLogger.info(DiagnosticCategory.FEED, "DISCOVERY_IMPRESSION", match { it["artifactId"] == "art-123" }) }
    }

    @Test
    fun `observePlaybackProgress logs depth yield proxy for discovery plays`() = runTest {
        val progressFlow = MutableStateFlow<ReviewProgress?>(null)
        every { audioPlayer.currentProgress } returns progressFlow
        
        val viewModel = createViewModel()
        
        // Simulate playing a discovery artifact
        val artifact = Artifact(id = "art-yield")
        viewModel.playAudio(artifact, FeedRecommendationReason.DISCOVERY)
        
        // Simulate reaching validation (95%+)
        progressFlow.value = ReviewProgress(
            artifactId = "art-yield",
            durationMs = 10000,
            coveragePercent = 0.95f,
            hasReachedEnd = true,
            isValidationMet = true,
            evidence = mockk(relaxed = true)
        )
        advanceUntilIdle()
        
        verify { diagnosticLogger.info(DiagnosticCategory.FEED, "DISCOVERY_DEPTH_YIELD", match { 
            it["artifactId"] == "art-yield" && it["milestone"] == "COMPLETE" 
        }) }
    }
}
