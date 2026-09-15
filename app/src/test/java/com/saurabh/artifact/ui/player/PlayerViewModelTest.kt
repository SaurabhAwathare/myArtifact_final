package com.saurabh.artifact.ui.player

import androidx.lifecycle.SavedStateHandle
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.ReviewSessionManager
import com.saurabh.artifact.audio.ReviewState
import com.saurabh.artifact.domain.review.publishing.PublishingReviewPolicy
import com.saurabh.artifact.domain.feed.ReactionUseCase
import com.saurabh.artifact.domain.player.DeleteArtifactUseCase
import com.saurabh.artifact.domain.player.GetPlayerContextUseCase
import com.saurabh.artifact.domain.player.PlayerMetadata
import com.saurabh.artifact.domain.player.PlayerInteractionUseCase
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.PlayableArtifact
import com.saurabh.artifact.model.PlaybackSource
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.PlayableArtifactRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val savedStateHandle = SavedStateHandle()
    private val playbackCoordinator = mockk<PlaybackCoordinator>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val reactionUseCase = mockk<ReactionUseCase>(relaxed = true)
    private val playerInteractionUseCase = mockk<PlayerInteractionUseCase>(relaxed = true)
    private val getPlayerContextUseCase = mockk<GetPlayerContextUseCase>(relaxed = true)
    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val playableArtifactRepository = mockk<PlayableArtifactRepository>(relaxed = true)
    private val reviewSessionManager = mockk<ReviewSessionManager>(relaxed = true)
    private val deleteArtifactUseCase = mockk<DeleteArtifactUseCase>(relaxed = true)
    private val publishingPolicy = mockk<PublishingReviewPolicy>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var viewModel: PlayerViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        // Mock required flows
        every { getPlayerContextUseCase.execute(any()) } returns flowOf(PlayerMetadata())
        every { playbackCoordinator.isPlaying } returns MutableStateFlow(false)
        every { playbackCoordinator.isBuffering } returns MutableStateFlow(false)
        every { playbackCoordinator.currentPosition } returns flowOf(0.seconds)
        every { playbackCoordinator.duration } returns flowOf(10.seconds)
        every { playbackCoordinator.smoothPosition } returns flowOf(0.seconds)
        every { playbackCoordinator.playbackSpeed } returns MutableStateFlow(1.0f)
        every { playbackCoordinator.isSkipSilenceEnabled } returns MutableStateFlow(false)
        every { playbackCoordinator.sleepTimerRemaining } returns MutableStateFlow(null)
        every { playbackCoordinator.currentArtifact } returns MutableStateFlow(null)
        every { playbackCoordinator.activePlayback } returns MutableStateFlow(null)
        every { playbackCoordinator.error } returns MutableSharedFlow()
        every { playbackCoordinator.playbackCompletedEvent } returns MutableSharedFlow()
        every { reviewSessionManager.reviewProgress } returns MutableStateFlow(ReviewState())
        every { authRepository.currentUser } returns MutableStateFlow(mockk(relaxed = true))
        every { authRepository.currentUserId } returns "user123"
        every { playbackCoordinator.currentProgress } returns MutableStateFlow(null)

        viewModel = PlayerViewModel(
            savedStateHandle, playbackCoordinator, authRepository, { reactionUseCase }, 
            { playerInteractionUseCase }, getPlayerContextUseCase, { playableArtifactRepository }, 
            reviewSessionManager, { deleteArtifactUseCase }, 
            publishingPolicy, diagnosticLogger
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `toggleSave should call playerInteractionUseCase when artifact is present`() = runTest {
        val artifact = Artifact(id = "art1", userId = "owner1")
        val currentArtifactFlow = MutableStateFlow<Artifact?>(artifact)
        val metadataFlow = MutableStateFlow(PlayerMetadata(artifactId = "art1"))
        
        every { playbackCoordinator.currentArtifact } returns currentArtifactFlow
        every { getPlayerContextUseCase.execute(any()) } returns metadataFlow
        
        // Re-create ViewModel to pick up the mocked flows properly during init
        val newViewModel = PlayerViewModel(
            savedStateHandle, playbackCoordinator, authRepository, { reactionUseCase }, 
            { playerInteractionUseCase }, getPlayerContextUseCase, { playableArtifactRepository }, 
            reviewSessionManager, { deleteArtifactUseCase },
            publishingPolicy, diagnosticLogger
        )
        
        // Start collecting uiState in backgroundScope (automatically cancelled at end of test)
        backgroundScope.launch {
            newViewModel.uiState.collect {}
        }
        
        // Yield to allow initialization and collection to start
        advanceUntilIdle()
        
        // Advance time to overcome WhileSubscribed(5000) delay
        advanceTimeBy(6000)
        
        newViewModel.toggleSave()
        advanceUntilIdle()
        
        verify { playerInteractionUseCase.toggleSave(match { it.id == "art1" }) }
    }

    @Test
    fun `playArtifactById should emit user friendly error on NotFound`() = runTest {
        val artifactId = "missing_id"
        val error = com.saurabh.artifact.model.AppError.NotFound("Artifact", artifactId)
        
        coEvery { 
            playableArtifactRepository.resolveArtifact(artifactId, any()) 
        } returns Result.failure(error)

        val errors = mutableListOf<String>()
        val collectJob = launch {
            viewModel.interactionError.collect { errors.add(it) }
        }

        viewModel.playArtifactById(artifactId)
        advanceUntilIdle()

        assert(errors.contains("This artifact is no longer available."))
        collectJob.cancel()
    }

    @Test
    fun `playArtifactById should skip if already playing the same artifact`() = runTest {
        val artifactId = "art1"
        val playable = mockk<com.saurabh.artifact.model.PlayableArtifact>(relaxed = true) {
            every { id } returns artifactId
            every { originalArtifact } returns mockk(relaxed = true) {
                every { id } returns artifactId
            }
        }
        
        coEvery { playableArtifactRepository.resolveArtifact(artifactId, any()) } returns Result.success(playable)
        
        // 1. Initial play
        viewModel.playArtifactById(artifactId)
        advanceUntilIdle()
        
        coVerify(exactly = 1) { playableArtifactRepository.resolveArtifact(artifactId, any()) }
        
        // 2. Mock that the coordinator now has this artifact
        every { playbackCoordinator.currentArtifact } returns MutableStateFlow(playable.originalArtifact)
        
        // 3. Second play with same ID
        viewModel.playArtifactById(artifactId)
        advanceUntilIdle()
        
        // Should NOT have called resolveArtifact again
        coVerify(exactly = 1) { playableArtifactRepository.resolveArtifact(artifactId, any()) }
    }

    @Test
    fun `playArtifactById should cancel previous job when new ID arrives`() = runTest {
        val id1 = "art1"
        val id2 = "art2"
        
        val artifact2 = Artifact(id = id2)
        val playable2 = mockk<PlayableArtifact>(relaxed = true) {
            every { id } returns id2
            every { originalArtifact } returns artifact2
        }

        coEvery { playableArtifactRepository.resolveArtifact(id1, any()) } coAnswers {
            kotlinx.coroutines.delay(1000) // Delay to simulate network
            Result.failure(Exception("Should be cancelled"))
        }
        coEvery { playableArtifactRepository.resolveArtifact(id2, any()) } returns Result.success(playable2)

        // Trigger first resolution
        viewModel.playArtifactById(id1)
        
        // Trigger second resolution immediately
        viewModel.playArtifactById(id2)
        
        advanceUntilIdle()
        
        // Verify only the second one reached completion/playback
        verify { playbackCoordinator.playArtifact(match { it.id == id2 }, any(), any(), any()) }
        verify(exactly = 0) { playbackCoordinator.playArtifact(match { it.id == id1 }, any(), any(), any()) }
    }

    @Test
    fun `playArtifactById resolving an Artifact eventually calls playbackCoordinator playArtifact`() = runTest {
        val artifactId = "art1"
        val artifact = Artifact(id = artifactId, title = "Test Artifact")
        val playable = mockk<PlayableArtifact>(relaxed = true) {
            every { id } returns artifactId
            every { originalArtifact } returns artifact
        }
        
        coEvery { playableArtifactRepository.resolveArtifact(artifactId, any()) } returns Result.success(playable)
        
        viewModel.playArtifactById(artifactId, PlaybackSource.NOTIFICATION)
        advanceUntilIdle()
        
        verify { playbackCoordinator.playArtifact(match { it.id == artifactId }, any(), any(), eq(PlaybackSource.NOTIFICATION)) }
    }

    @Test
    fun `currentPlayableArtifact matching the Artifact does NOT by itself cause PLAYER_RE-ENTRY_SKIPPED`() = runTest {
        val artifactId = "art1"
        val artifact = Artifact(id = artifactId, title = "Test Artifact")
        val playable = mockk<PlayableArtifact>(relaxed = true) {
            every { id } returns artifactId
            every { originalArtifact } returns artifact
        }
        
        coEvery { playableArtifactRepository.resolveArtifact(artifactId, any()) } returns Result.success(playable)
        
        // playbackCoordinator.currentArtifact remains null (not currently playing)
        every { playbackCoordinator.currentArtifact } returns MutableStateFlow(null)
        
        // Calling playArtifactById populates _currentPlayableArtifact then calls playArtifact()
        viewModel.playArtifactById(artifactId)
        advanceUntilIdle()
        
        // Even though _currentPlayableArtifact was populated with playable (id = "art1"),
        // playArtifact MUST NOT be skipped and MUST call playbackCoordinator.playArtifact
        verify(exactly = 1) { playbackCoordinator.playArtifact(match { it.id == artifactId }, any(), any(), any()) }
    }

    @Test
    fun `playbackCoordinator currentArtifact matching the Artifact correctly prevents unnecessary re-entry`() = runTest {
        val artifactId = "art1"
        val artifact = Artifact(id = artifactId)
        
        // Currently playing in playbackCoordinator
        every { playbackCoordinator.currentArtifact } returns MutableStateFlow(artifact)
        
        // Calling playArtifact directly
        viewModel.playArtifact(artifact)
        advanceUntilIdle()
        
        // Calling playArtifactById directly
        viewModel.playArtifactById(artifactId)
        advanceUntilIdle()
        
        // Neither call should invoke playbackCoordinator.playArtifact or resolveArtifact
        verify(exactly = 0) { playbackCoordinator.playArtifact(any(), any(), any(), any()) }
        coVerify(exactly = 0) { playableArtifactRepository.resolveArtifact(any(), any()) }
    }

    @Test
    fun `notification playback path can initialize playback coordinator`() = runTest {
        val artifactId = "notification_art_123"
        val artifact = Artifact(id = artifactId, title = "Notification Audio")
        val playable = mockk<PlayableArtifact>(relaxed = true) {
            every { id } returns artifactId
            every { originalArtifact } returns artifact
        }
        
        coEvery { 
            playableArtifactRepository.resolveArtifact(artifactId, PlaybackSource.NOTIFICATION)
        } returns Result.success(playable)
        
        every { playbackCoordinator.currentArtifact } returns MutableStateFlow(null)
        
        viewModel.playArtifactById(artifactId, PlaybackSource.NOTIFICATION)
        advanceUntilIdle()
        
        verify { 
            playbackCoordinator.playArtifact(
                artifact = match { it.id == artifactId },
                collection = any(),
                initialPosition = any(),
                source = eq(PlaybackSource.NOTIFICATION)
            ) 
        }
    }

    @Test
    fun `existing Profile and normal Feed playback behavior remains unchanged`() = runTest {
        val profileArtifact = Artifact(id = "profile_art_1")
        val feedArtifact = Artifact(id = "feed_art_1")
        
        every { playbackCoordinator.currentArtifact } returns MutableStateFlow(null)
        
        // Profile playback
        viewModel.playArtifact(profileArtifact, source = PlaybackSource.PROFILE_PLAYBACK)
        advanceUntilIdle()
        
        verify { 
            playbackCoordinator.playArtifact(
                artifact = match { it.id == "profile_art_1" },
                collection = any(),
                initialPosition = any(),
                source = eq(PlaybackSource.PROFILE_PLAYBACK)
            )
        }
        
        // Feed playback
        viewModel.playArtifact(feedArtifact, source = PlaybackSource.FEED_PLAYBACK)
        advanceUntilIdle()
        
        verify { 
            playbackCoordinator.playArtifact(
                artifact = match { it.id == "feed_art_1" },
                collection = any(),
                initialPosition = any(),
                source = eq(PlaybackSource.FEED_PLAYBACK)
            )
        }
    }

    @Test
    fun `no duplicate playback initialization is introduced`() = runTest {
        val artifactId = "art1"
        val currentArtifactFlow = MutableStateFlow<Artifact?>(null)
        val artifact = Artifact(id = artifactId)
        val playable = mockk<PlayableArtifact>(relaxed = true) {
            every { id } returns artifactId
            every { originalArtifact } returns artifact
        }
        
        every { playbackCoordinator.currentArtifact } returns currentArtifactFlow
        coEvery { playableArtifactRepository.resolveArtifact(artifactId, any()) } returns Result.success(playable)
        
        // Simulate playbackCoordinator updating currentArtifact once playArtifact is called
        every { playbackCoordinator.playArtifact(any(), any(), any(), any()) } answers {
            currentArtifactFlow.value = artifact
        }
        
        // First playArtifactById call
        viewModel.playArtifactById(artifactId)
        advanceUntilIdle()
        
        // Second playArtifactById call for same artifact
        viewModel.playArtifactById(artifactId)
        advanceUntilIdle()
        
        // Should only be called ONCE
        verify(exactly = 1) { playbackCoordinator.playArtifact(match { it.id == artifactId }, any(), any(), any()) }
    }
}
