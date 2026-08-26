package com.saurabh.artifact.ui.comment

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.google.firebase.firestore.DocumentSnapshot
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.domain.artifact.ArtifactOwnershipAuthority
import com.saurabh.artifact.domain.comment.AddCommentUseCase
import com.saurabh.artifact.domain.comment.DeleteCommentUseCase
import com.saurabh.artifact.domain.comment.GetCommentsUseCase
import com.saurabh.artifact.model.Comment
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.EngagementRepository
import com.saurabh.artifact.repository.PaginatedComments
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommentViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    
    private lateinit var viewModel: CommentViewModel
    private val getCommentsUseCase: GetCommentsUseCase = mockk()
    private val addCommentUseCase: AddCommentUseCase = mockk()
    private val deleteCommentUseCase: DeleteCommentUseCase = mockk()
    private val engagementRepository: EngagementRepository = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val ownershipAuthority: ArtifactOwnershipAuthority = mockk()
    private val diagnosticLogger: com.saurabh.artifact.diagnostics.DiagnosticLogger = mockk(relaxed = true)
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(mapOf("artifactId" to "test-artifact"))
    private val currentUserFlow = MutableStateFlow<com.google.firebase.auth.FirebaseUser?>(null)

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0

        Dispatchers.setMain(testDispatcher)
        
        // Default mock for initial load
        coEvery { getCommentsUseCase("test-artifact", any(), any()) } returns Result.success(
            PaginatedComments(emptyList(), null),
        )
        coEvery { ownershipAuthority.isCurrentUserOwner("test-artifact") } returns false
        every { engagementRepository.observeEngagementEvidence(any()) } returns flowOf(null)
        every { authRepository.currentUser } returns currentUserFlow
        every { authRepository.currentUserId } returns (currentUserFlow.value?.uid ?: "")
        
        viewModel = CommentViewModel(
            savedStateHandle,
            getCommentsUseCase,
            addCommentUseCase,
            deleteCommentUseCase,
            engagementRepository,
            authRepository,
            ownershipAuthority,
            diagnosticLogger
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
        currentUserFlow.value = null
    }

    @Test
    fun `account change clears comments and state`() = runTest {
        val userA = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns "user-a" }
        val userB = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns "user-b" }
        
        // 1. User A loads comments
        currentUserFlow.value = userA
        val c1 = Comment(id = "c1", text = "From A")
        coEvery { getCommentsUseCase("test-artifact", any(), any()) } returns Result.success(
            PaginatedComments(listOf(c1), null)
        )
        
        viewModel.loadInitialComments()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.comments.size)
        
        // 2. Switch to User B
        currentUserFlow.value = userB
        testDispatcher.scheduler.advanceUntilIdle()
        
        // 3. Verify state is cleared
        assertEquals(0, viewModel.uiState.value.comments.size)
        // Note: initialize might be called by UI later, but VM should clear on auth change regardless
    }

    @Test
    fun `logout clears user sensitive state`() = runTest {
        val userA = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns "user-a" }
        currentUserFlow.value = userA
        
        val c1 = Comment(id = "c1", text = "From A")
        coEvery { getCommentsUseCase("test-artifact", any(), any()) } returns Result.success(
            PaginatedComments(listOf(c1), null)
        )
        
        viewModel.loadInitialComments()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.comments.size)
        
        // Logout
        currentUserFlow.value = null
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertEquals(0, viewModel.uiState.value.comments.size)
    }

    @Test
    fun `stale request from previous user is rejected`() = runTest {
        val userA = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns "user-a" }
        val userB = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns "user-b" }
        
        currentUserFlow.value = userA
        every { authRepository.currentUserId } returns "user-a"

        val commentA = Comment(id = "a1", text = "A's comment")
        
        // Mock a slow load for User A
        coEvery { getCommentsUseCase("test-artifact", any(), any()) } coAnswers {
            delay(1000)
            Result.success(PaginatedComments(listOf(commentA), null))
        }
        
        viewModel.loadInitialComments()
        
        // Advance time but not enough to finish
        testDispatcher.scheduler.advanceTimeBy(500)
        
        // Switch to User B
        currentUserFlow.value = userB
        every { authRepository.currentUserId } returns "user-b"
        testDispatcher.scheduler.advanceUntilIdle() // This triggers the auth observer which resets state
        
        // Finish User A's request
        testDispatcher.scheduler.advanceTimeBy(1000)
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Verify User A's comment didn't leak into User B's state
        assertEquals(0, viewModel.uiState.value.comments.size)
        verify { diagnosticLogger.warn(DiagnosticCategory.COMMENT, "STALE_LOAD_REJECTED", any()) }
    }

    @Test
    fun `initialize with different artifact clears pagination cursor`() = runTest {
        val c1 = Comment(id = "1", text = "C1")
        val cursorX = mockk<DocumentSnapshot>()
        
        coEvery { getCommentsUseCase("artifact-x", any(), any()) } returns Result.success(
            PaginatedComments(listOf(c1), cursorX)
        )
        
        viewModel.initialize("artifact-x")
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Verify we have a cursor internally (implicitly by hasMorePages)
        assertEquals(true, viewModel.uiState.value.hasMorePages)
        
        // Switch to Artifact Y
        coEvery { getCommentsUseCase("artifact-y", any(), isNull()) } returns Result.success(
            PaginatedComments(emptyList(), null)
        )
        
        viewModel.initialize("artifact-y")
        
        // Verify comments are cleared synchronously even before load finishes
        assertEquals(0, viewModel.uiState.value.comments.size)
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Verify lastVisibleCursor is null (hasMorePages becomes false)
        assertEquals(false, viewModel.uiState.value.hasMorePages)
    }

    @Test
    fun `submitComment followed by refresh produces duplicate if not handled`() = runTest {
        val commentId = "duplicate-id"
        val comment = Comment(id = commentId, text = "Test Comment")
        
        // 1. Mock AddComment to be slow
        coEvery { addCommentUseCase("test-artifact", "Test Comment") } coAnswers {
            // Wait for refresh to potentially finish
            testDispatcher.scheduler.advanceTimeBy(100)
            Result.success(comment)
        }
        
        // 2. Mock GetComments to return the SAME comment (as if it was already synced)
        coEvery { getCommentsUseCase("test-artifact", any(), any()) } returns Result.success(
            PaginatedComments(listOf(comment), null)
        )
        
        // 3. Trigger submit
        viewModel.submitComment("Test Comment")
        
        // 4. Trigger refresh while submit is in progress
        viewModel.refreshComments()
        
        // Advance time to allow both to finish
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = viewModel.uiState.value
        val commentIds = state.comments.map { it.id }
        
        // If duplicates exist, this will fail (currently it has [duplicate-id, duplicate-id])
        assertEquals("Should have only 1 unique comment ID", 1, commentIds.size)
        assertEquals("Should have unique comment IDs", commentIds.distinct().size, commentIds.size)
    }

    @Test
    fun `loadNextPage with overlapping results produces duplicates if not handled`() = runTest {
        val c1 = Comment(id = "1", text = "C1")
        val c2 = Comment(id = "2", text = "C2")
        val c3 = Comment(id = "3", text = "C3")
        
        // Initial load returns C1, C2
        coEvery { getCommentsUseCase("test-artifact", any(), isNull()) } returns Result.success(
            PaginatedComments(listOf(c1, c2), mockk()) // lastVisible is not null
        )
        
        viewModel.loadInitialComments()
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Next page returns C2, C3 (overlap with C2)
        coEvery { getCommentsUseCase("test-artifact", any(), any()) } returns Result.success(
            PaginatedComments(listOf(c2, c3), null)
        )
        
        viewModel.loadNextPage()
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = viewModel.uiState.value
        val commentIds = state.comments.map { it.id }
        
        // Currently it would be [1, 2, 2, 3]
        assertEquals("Should have 3 unique comment IDs", 3, commentIds.size)
    }

    @Test
    fun `owner should have UNLOCKED state immediately`() = runTest {
        val artifactId = "owner-artifact"
        coEvery { ownershipAuthority.isCurrentUserOwner(artifactId) } returns true
        coEvery { getCommentsUseCase(artifactId, any(), any()) } returns Result.success(
            PaginatedComments(emptyList(), null)
        )
        
        viewModel.initialize(artifactId)
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertEquals(CommentUnlockState.UNLOCKED, viewModel.uiState.value.unlockState)
    }

    @Test
    fun `non-owner without engagement should have LOCKED state`() = runTest {
        val artifactId = "other-artifact"
        coEvery { ownershipAuthority.isCurrentUserOwner(artifactId) } returns false
        coEvery { getCommentsUseCase(artifactId, any(), any()) } returns Result.success(
            PaginatedComments(emptyList(), null)
        )
        // engagementRepository mock is relaxed, so it returns an empty flow or nulls
        
        viewModel.initialize(artifactId)
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertEquals(CommentUnlockState.LOCKED, viewModel.uiState.value.unlockState)
    }

    @Test
    fun `submitComment failure emits SubmissionFailed event`() = runTest {
        val errorMsg = "Network Error"
        coEvery { addCommentUseCase("test-artifact", "Test") } returns Result.failure(Exception(errorMsg))
        
        val events = mutableListOf<CommentUiEvent>()
        val job = launch {
            viewModel.events.collect { events.add(it) }
        }
        
        viewModel.submitComment("Test")
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertEquals(1, events.size)
        val event = events[0] as CommentUiEvent.SubmissionFailed
        assertEquals("Network Error", event.error) 
        
        job.cancel()
    }
}
