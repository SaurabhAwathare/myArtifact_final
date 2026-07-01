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
import com.saurabh.artifact.domain.review.comments.CommentUnlockPolicy
import com.saurabh.artifact.model.Artifact
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
import android.util.Log
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
    private val commentPolicy = mockk<CommentUnlockPolicy>(relaxed = true)

    private lateinit var viewModel: PlayerViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.v(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

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
        every { commentPolicy.minCoverage } returns 0.95f
        every { authRepository.currentUser } returns MutableStateFlow(mockk(relaxed = true))
        every { authRepository.currentUserId } returns "user123"
        every { playbackCoordinator.currentProgress } returns MutableStateFlow(null)

    viewModel = PlayerViewModel(
        savedStateHandle, playbackCoordinator, authRepository, { reactionUseCase }, 
        { playerInteractionUseCase }, getPlayerContextUseCase, { playableArtifactRepository }, 
        reviewSessionManager, { deleteArtifactUseCase }, 
        publishingPolicy, commentPolicy
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
            publishingPolicy, commentPolicy
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
    fun `playArtifactById should emit user friendly error on PermissionDenied`() = runTest {
        val artifactId = "private_id"
        val error = com.saurabh.artifact.model.AppError.PermissionDenied()
        
        coEvery { 
            playableArtifactRepository.resolveArtifact(artifactId, any()) 
        } returns Result.failure(error)

        val errors = mutableListOf<String>()
        val collectJob = launch {
            viewModel.interactionError.collect { errors.add(it) }
        }

        viewModel.playArtifactById(artifactId)
        advanceUntilIdle()

        assert(errors.contains("This artifact isn't available to you."))
        collectJob.cancel()
    }
}
