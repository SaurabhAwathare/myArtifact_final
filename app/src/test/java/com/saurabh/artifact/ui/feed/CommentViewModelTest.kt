package com.saurabh.artifact.ui.feed

import android.content.Context
import com.saurabh.artifact.audio.ReviewAuthorityService
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.data.local.PendingInteractionEntity
import com.saurabh.artifact.domain.review.GetEngagementStateUseCase
import com.saurabh.artifact.domain.review.comments.CommentUnlockPolicy
import com.saurabh.artifact.model.EngagementStatus
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.CommentRepository
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.security.UploadGuard
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommentViewModelTest {

    private val context = mockk<Context>(relaxed = true)
    private val repository = mockk<CommentRepository>(relaxed = true)
    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val auth = mockk<AuthRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val getEngagementStateUseCase = mockk<GetEngagementStateUseCase>()
    private val reviewAuthorityService = mockk<ReviewAuthorityService>(relaxed = true)
    private val uploadGuard = mockk<UploadGuard>(relaxed = true)
    private val commentUnlockPolicy = mockk<CommentUnlockPolicy>(relaxed = true)

    private lateinit var viewModel: CommentViewModel
    private val testDispatcher = StandardTestDispatcher()

    private val artifactId = "art123"
    private val ownerId = "owner456"

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0

        Dispatchers.setMain(testDispatcher)
        every { auth.currentUserId } returns "user789"
        every { commentUnlockPolicy.minCoverage } returns 0.95f
        every { reviewAuthorityService.currentProgress } returns MutableStateFlow(null)

        viewModel = CommentViewModel(
            context, repository, artifactRepository, auth, userRepository,
            getEngagementStateUseCase, reviewAuthorityService, uploadGuard, commentUnlockPolicy
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadComments observes engagement status and updates UI`() = runTest {
        val statusFlow = MutableStateFlow(EngagementStatus.LOCKED)
        every { getEngagementStateUseCase.execute(artifactId) } returns statusFlow

        viewModel.loadComments(artifactId, ownerId)
        advanceUntilIdle()
        assertEquals(EngagementStatus.LOCKED, viewModel.uiState.value.engagementStatus)

        statusFlow.value = EngagementStatus.VERIFYING
        advanceUntilIdle()
        assertEquals(EngagementStatus.VERIFYING, viewModel.uiState.value.engagementStatus)

        statusFlow.value = EngagementStatus.UNLOCKED
        advanceUntilIdle()
        assertEquals(EngagementStatus.UNLOCKED, viewModel.uiState.value.engagementStatus)
    }

    @Test
    fun `long server delay preserves VERIFYING state`() = runTest {
        val statusFlow = MutableStateFlow(EngagementStatus.VERIFYING)
        every { getEngagementStateUseCase.execute(artifactId) } returns statusFlow

        viewModel.loadComments(artifactId, ownerId)
        
        // Simulate long delay
        advanceTimeBy(30000) 
        runCurrent()
        
        assertEquals(EngagementStatus.VERIFYING, viewModel.uiState.value.engagementStatus)
        
        // Finally unlock
        statusFlow.value = EngagementStatus.UNLOCKED
        advanceUntilIdle()
        assertEquals(EngagementStatus.UNLOCKED, viewModel.uiState.value.engagementStatus)
    }

    @Test
    fun `process recreation (re-init) restores state from use case`() = runTest {
        // Mock state in repositories/usecase
        every { getEngagementStateUseCase.execute(artifactId) } returns flowOf(EngagementStatus.VERIFYING)
        
        // Create new ViewModel instance (simulating process recreation)
        val newViewModel = CommentViewModel(
            context, repository, artifactRepository, auth, userRepository,
            getEngagementStateUseCase, reviewAuthorityService, uploadGuard, commentUnlockPolicy
        )
        
        newViewModel.loadComments(artifactId, ownerId)
        advanceUntilIdle()
        
        assertEquals(EngagementStatus.VERIFYING, newViewModel.uiState.value.engagementStatus)
    }

    @Test
    fun `loadComments triggers refresh when transitioning to UNLOCKED`() = runTest {
        val statusFlow = MutableStateFlow(EngagementStatus.LOCKED)
        every { getEngagementStateUseCase.execute(artifactId) } returns statusFlow
        
        // Use a real Pager flow to track emissions if possible, or verify repository call
        // But here we want to verify the internal _refreshTrigger.
        // Since _refreshTrigger is private, we verify the side effect: repository.getCommentsPager being called again.
        
        viewModel.loadComments(artifactId, ownerId)
        advanceUntilIdle()
        
        // Initial load (1 call)
        verify(exactly = 1) { repository.getCommentsPager(artifactId, any(), ownerId) }
        
        // Transition to UNLOCKED
        statusFlow.value = EngagementStatus.UNLOCKED
        advanceUntilIdle()
        
        // Should trigger refresh (2nd call)
        verify(exactly = 2) { repository.getCommentsPager(artifactId, any(), ownerId) }
    }
}
