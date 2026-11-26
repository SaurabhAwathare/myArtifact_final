package com.saurabh.artifact.ui.player

import androidx.lifecycle.SavedStateHandle
import com.google.firebase.auth.FirebaseUser
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
import com.saurabh.artifact.model.User
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.PlayableArtifactRepository
import dagger.Lazy
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
import org.junit.Assert
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
        val mockFirebaseUser = mockk<FirebaseUser>(relaxed = true) {
            every { uid } returns "user123"
        }
        val testUser = User(id = "user123", anonymousId = "anon123")
        every { reviewSessionManager.reviewProgress } returns MutableStateFlow(ReviewState())
        every { authRepository.currentUser } returns MutableStateFlow(mockFirebaseUser)
        every { authRepository.userData } returns MutableStateFlow(testUser)
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
    fun `toggleSave should not call playerInteractionUseCase when user is owner`() = runTest {
        // Current user is "user123", artifact owner is "user123" -> isOwner is true
        val artifact = Artifact(id = "art_owner", userId = "user123")
        val currentArtifactFlow = MutableStateFlow<Artifact?>(artifact)
        val metadataFlow = MutableStateFlow(PlayerMetadata(artifactId = "art_owner"))
        
        every { playbackCoordinator.currentArtifact } returns currentArtifactFlow
        every { getPlayerContextUseCase.execute(any()) } returns metadataFlow
        
        val ownerViewModel = PlayerViewModel(
            savedStateHandle, playbackCoordinator, authRepository, { reactionUseCase }, 
            { playerInteractionUseCase }, getPlayerContextUseCase, { playableArtifactRepository }, 
            reviewSessionManager, { deleteArtifactUseCase },
            publishingPolicy, diagnosticLogger
        )
        
        backgroundScope.launch {
            ownerViewModel.uiState.collect {}
        }
        
        advanceUntilIdle()
        advanceTimeBy(6000)
        
        ownerViewModel.toggleSave()
        advanceUntilIdle()
        
        verify(exactly = 0) { playerInteractionUseCase.toggleSave(any()) }
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

    @Test
    fun `toggleResonanceConnection calls playerInteractionUseCase when valid artifact present`() = runTest {
        val artifact = Artifact(
            id = "art1",
            userId = "other_user",
            author = com.saurabh.artifact.model.AuthorSnapshot(anonymousId = "creator_anon_1")
        )
        val currentArtifactFlow = MutableStateFlow<Artifact?>(artifact)
        every { playbackCoordinator.currentArtifact } returns currentArtifactFlow
        coEvery {
            playerInteractionUseCase.toggleResonanceConnection("user123", "creator_anon_1", false)
        } returns Result.success(Unit)

        val newViewModel = PlayerViewModel(
            savedStateHandle, playbackCoordinator, authRepository, { reactionUseCase }, 
            { playerInteractionUseCase }, getPlayerContextUseCase, { playableArtifactRepository }, 
            reviewSessionManager, { deleteArtifactUseCase }, 
            publishingPolicy, diagnosticLogger
        )
        backgroundScope.launch {
            newViewModel.uiState.collect {}
        }
        advanceUntilIdle()
        advanceTimeBy(6000)

        newViewModel.toggleResonanceConnection()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            playerInteractionUseCase.toggleResonanceConnection("user123", "creator_anon_1", false)
        }
    }

    @Test
    fun `toggleResonanceConnection reports error on failure`() = runTest {
        val artifact = Artifact(
            id = "art1",
            userId = "other_user",
            author = com.saurabh.artifact.model.AuthorSnapshot(anonymousId = "creator_anon_1")
        )
        val currentArtifactFlow = MutableStateFlow<Artifact?>(artifact)
        every { playbackCoordinator.currentArtifact } returns currentArtifactFlow
        val error = com.saurabh.artifact.model.AppError.NetworkFailure()
        coEvery {
            playerInteractionUseCase.toggleResonanceConnection("user123", "creator_anon_1", false)
        } returns Result.failure(error)

        val newViewModel = PlayerViewModel(
            savedStateHandle, playbackCoordinator, authRepository, { reactionUseCase }, 
            { playerInteractionUseCase }, getPlayerContextUseCase, { playableArtifactRepository }, 
            reviewSessionManager, { deleteArtifactUseCase }, 
            publishingPolicy, diagnosticLogger
        )
        backgroundScope.launch {
            newViewModel.uiState.collect {}
        }
        val errors = mutableListOf<String>()
        val collectJob = launch {
            newViewModel.interactionError.collect { errors.add(it) }
        }
        advanceUntilIdle()
        advanceTimeBy(6000)

        newViewModel.toggleResonanceConnection()
        advanceUntilIdle()

        Assert.assertTrue(errors.contains("Connection lost. Please check your network."))
        collectJob.cancel()
    }

    @Test
    fun `uiState should reflect qualifiedPlayCount from metadata when metadata is synced`() = runTest {
        val artifact = Artifact(id = "art_qual_vm", userId = "owner1", qualifiedPlayCount = 10L)
        val currentArtifactFlow = MutableStateFlow<Artifact?>(artifact)
        val metadataFlow = MutableStateFlow(PlayerMetadata(artifactId = "art_qual_vm", qualifiedPlayCount = 10L))

        every { playbackCoordinator.currentArtifact } returns currentArtifactFlow
        every { getPlayerContextUseCase.execute(any()) } returns metadataFlow

        val vm = PlayerViewModel(
            savedStateHandle, playbackCoordinator, authRepository, { reactionUseCase },
            { playerInteractionUseCase }, getPlayerContextUseCase, { playableArtifactRepository },
            reviewSessionManager, { deleteArtifactUseCase },
            publishingPolicy, diagnosticLogger
        )
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        advanceTimeBy(6000)

        Assert.assertEquals(10L, vm.uiState.value.qualifiedPlayCount)
    }

    @Test
    fun `uiState should update qualifiedPlayCount when metadata snapshot updates while player is open`() = runTest {
        val artifact = Artifact(id = "art_live_vm", userId = "owner1", qualifiedPlayCount = 3L)
        val currentArtifactFlow = MutableStateFlow<Artifact?>(artifact)
        val metadataFlow = MutableStateFlow(PlayerMetadata(artifactId = "art_live_vm", qualifiedPlayCount = 3L))

        every { playbackCoordinator.currentArtifact } returns currentArtifactFlow
        every { getPlayerContextUseCase.execute(any()) } returns metadataFlow

        val vm = PlayerViewModel(
            savedStateHandle, playbackCoordinator, authRepository, { reactionUseCase },
            { playerInteractionUseCase }, getPlayerContextUseCase, { playableArtifactRepository },
            reviewSessionManager, { deleteArtifactUseCase },
            publishingPolicy, diagnosticLogger
        )
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        advanceTimeBy(6000)

        Assert.assertEquals(3L, vm.uiState.value.qualifiedPlayCount)

        // Snapshot update arrives while player remains open
        metadataFlow.value = PlayerMetadata(artifactId = "art_live_vm", qualifiedPlayCount = 4L)
        advanceUntilIdle()

        Assert.assertEquals(4L, vm.uiState.value.qualifiedPlayCount)
    }

    @Test
    fun `switching artifacts should not display previous artifact qualifiedPlayCount`() = runTest {
        val artifact1 = Artifact(id = "art_1", userId = "owner1", qualifiedPlayCount = 99L)
        val artifact2 = Artifact(id = "art_2", userId = "owner2", qualifiedPlayCount = 0L)
        val currentArtifactFlow = MutableStateFlow<Artifact?>(artifact1)
        val metadataFlow = MutableStateFlow(PlayerMetadata(artifactId = "art_1", qualifiedPlayCount = 99L))

        every { playbackCoordinator.currentArtifact } returns currentArtifactFlow
        every { getPlayerContextUseCase.execute(any()) } returns metadataFlow

        val vm = PlayerViewModel(
            savedStateHandle, playbackCoordinator, authRepository, { reactionUseCase },
            { playerInteractionUseCase }, getPlayerContextUseCase, { playableArtifactRepository },
            reviewSessionManager, { deleteArtifactUseCase },
            publishingPolicy, diagnosticLogger
        )
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        advanceTimeBy(6000)

        Assert.assertEquals(99L, vm.uiState.value.qualifiedPlayCount)

        // Switch to artifact2
        currentArtifactFlow.value = artifact2
        // Metadata not yet updated for artifact2 (still has art_1)
        advanceUntilIdle()

        // Because md.artifactId ("art_1") != artifact.id ("art_2"), isMetadataSynced is false!
        // It must NOT show artifact1's count (99L); it falls back safely to artifact2's count (0L)
        Assert.assertEquals(0L, vm.uiState.value.qualifiedPlayCount)

        // Now metadata for artifact2 arrives
        metadataFlow.value = PlayerMetadata(artifactId = "art_2", qualifiedPlayCount = 2L)
        advanceUntilIdle()

        Assert.assertEquals(2L, vm.uiState.value.qualifiedPlayCount)
    }

    @Test
    fun `loading state and null artifact should not fabricate qualifiedPlayCount`() = runTest {
        val currentArtifactFlow = MutableStateFlow<Artifact?>(null)
        val metadataFlow = MutableStateFlow(PlayerMetadata())

        every { playbackCoordinator.currentArtifact } returns currentArtifactFlow
        every { getPlayerContextUseCase.execute(any()) } returns metadataFlow

        val vm = PlayerViewModel(
            savedStateHandle, playbackCoordinator, authRepository, { reactionUseCase },
            { playerInteractionUseCase }, getPlayerContextUseCase, { playableArtifactRepository },
            reviewSessionManager, { deleteArtifactUseCase },
            publishingPolicy, diagnosticLogger
        )
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        advanceTimeBy(6000)

        Assert.assertEquals(0L, vm.uiState.value.qualifiedPlayCount)
    }

    @Test
    fun `metadata observation recovers on next artifact after single artifact failure`() = runTest {
        val badArtifact = Artifact(id = "art_bad", userId = "owner1", qualifiedPlayCount = 0L)
        val goodArtifact = Artifact(id = "art_good", userId = "owner2", qualifiedPlayCount = 4L)
        val currentArtifactFlow = MutableStateFlow<Artifact?>(badArtifact)

        every { playbackCoordinator.currentArtifact } returns currentArtifactFlow
        every { getPlayerContextUseCase.execute(any()) } answers {
            kotlinx.coroutines.flow.flow {
                val current = currentArtifactFlow.value
                if (current?.id == "art_bad") {
                    throw RuntimeException("Simulated PERMISSION_DENIED")
                } else if (current != null) {
                    emit(PlayerMetadata(artifactId = current.id, qualifiedPlayCount = current.qualifiedPlayCount))
                }
            }
        }

        val vm = PlayerViewModel(
            savedStateHandle, playbackCoordinator, authRepository, { reactionUseCase },
            { playerInteractionUseCase }, getPlayerContextUseCase, { playableArtifactRepository },
            reviewSessionManager, { deleteArtifactUseCase },
            publishingPolicy, diagnosticLogger
        )
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        advanceTimeBy(1000)

        // Switch to a valid artifact after the previous metadata flow threw
        currentArtifactFlow.value = goodArtifact
        advanceUntilIdle()
        advanceTimeBy(1000)

        Assert.assertEquals(4L, vm.uiState.value.qualifiedPlayCount)
    }

    @Test
    fun `qualifiedPlayCount and 95 percent comment unlock state remain independent`() = runTest {
        val artifact = Artifact(
            id = "art_indep",
            userId = "owner1",
            status = com.saurabh.artifact.model.ArtifactStatus.ACTIVE,
            qualifiedPlayCount = 3L
        )
        val currentArtifactFlow = MutableStateFlow<Artifact?>(artifact)
        val metadataFlow = MutableStateFlow(PlayerMetadata(artifactId = "art_indep", qualifiedPlayCount = 3L))
        val evidence = com.saurabh.artifact.domain.review.EngagementEvidence(
            artifactId = "art_indep",
            versionTag = "v1",
            durationMs = 60_000L
        )
        val progressFlow = MutableStateFlow(
            com.saurabh.artifact.audio.validation.ReviewProgress(
                artifactId = "art_indep",
                durationMs = 60_000L,
                coveragePercent = 0.25f,
                hasReachedEnd = false,
                isValidationMet = false,
                evidence = evidence
            )
        )

        every { playbackCoordinator.currentArtifact } returns currentArtifactFlow
        every { getPlayerContextUseCase.execute(any()) } returns metadataFlow
        every { playbackCoordinator.currentProgress } returns progressFlow

        val vm = PlayerViewModel(
            savedStateHandle, playbackCoordinator, authRepository, { reactionUseCase },
            { playerInteractionUseCase }, getPlayerContextUseCase, { playableArtifactRepository },
            reviewSessionManager, { deleteArtifactUseCase },
            publishingPolicy, diagnosticLogger
        )
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        advanceTimeBy(1000)

        // Qualified play count updates at 25% coverage even though 95% comment unlock is not met
        Assert.assertEquals(3L, vm.uiState.value.qualifiedPlayCount)
        Assert.assertFalse(vm.uiState.value.isThresholdMet)

        // Comment unlock reaching 95% does not alter authoritative qualifiedPlayCount
        progressFlow.value = com.saurabh.artifact.audio.validation.ReviewProgress(
            artifactId = "art_indep",
            durationMs = 60_000L,
            coveragePercent = 0.96f,
            hasReachedEnd = true,
            isValidationMet = true,
            evidence = evidence
        )
        advanceUntilIdle()

        Assert.assertEquals(3L, vm.uiState.value.qualifiedPlayCount)
        Assert.assertTrue(vm.uiState.value.isThresholdMet)
    }
}
