package com.saurabh.artifact.ui.comment

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.saurabh.artifact.domain.artifact.ArtifactOwnershipAuthority
import com.saurabh.artifact.domain.comment.AddCommentUseCase
import com.saurabh.artifact.domain.comment.DeleteCommentUseCase
import com.saurabh.artifact.domain.comment.GetCommentsUseCase
import com.saurabh.artifact.model.Comment
import com.saurabh.artifact.repository.EngagementRepository
import com.saurabh.artifact.repository.PaginatedComments
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
    private val ownershipAuthority: ArtifactOwnershipAuthority = mockk()
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(mapOf("artifactId" to "test-artifact"))

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
        
        viewModel = CommentViewModel(
            savedStateHandle,
            getCommentsUseCase,
            addCommentUseCase,
            deleteCommentUseCase,
            engagementRepository,
            ownershipAuthority
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
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
}
