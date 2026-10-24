package com.saurabh.artifact.ui.comment

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.domain.artifact.ArtifactOwnershipAuthority
import com.saurabh.artifact.domain.comment.AddCommentUseCase
import com.saurabh.artifact.domain.comment.DeleteCommentUseCase
import com.saurabh.artifact.domain.comment.GetCommentsUseCase
import com.saurabh.artifact.model.Comment
import com.saurabh.artifact.repository.ArtifactModerationRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.EngagementRepository
import com.saurabh.artifact.repository.PaginatedComments
import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.domain.review.UnlockStatus
import com.saurabh.artifact.model.SyncState
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.data.local.DeadLetterInteractionDao
import com.saurabh.artifact.data.local.DeadLetterInteractionEntity
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.remote.model.CommentPayload
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.CommentStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert
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
    private val moderationRepository: ArtifactModerationRepository = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val ownershipAuthority: ArtifactOwnershipAuthority = mockk()
    private val userRepository: UserRepository = mockk(relaxed = true)
    private val deadLetterInteractionDao: DeadLetterInteractionDao = mockk(relaxed = true)
    private val diagnosticLogger: DiagnosticLogger = mockk(relaxed = true)
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(mapOf("artifactId" to "test-artifact"))
    private val currentUserFlow = MutableStateFlow<FirebaseUser?>(null)
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0

        Dispatchers.setMain(testDispatcher)
        
        // Default mock for initial load
        coEvery { getCommentsUseCase(any(), any(), any()) } returns Result.success(
            PaginatedComments(emptyList(), null),
        )
        coEvery { ownershipAuthority.isCurrentUserOwner(any()) } returns false
        coEvery { userRepository.resolveCreatorProfilesBatch(any()) } returns emptyMap()
        every { engagementRepository.observeEngagementEvidence(any()) } returns flowOf(null)
        every { authRepository.currentUser } returns currentUserFlow
        every { authRepository.currentUserId } returns (currentUserFlow.value?.uid ?: "")
        every { deadLetterInteractionDao.observeDeadLettersForArtifact(any(), any(), any()) } returns flowOf(emptyList())
        
        viewModel = CommentViewModel(
            savedStateHandle,
            getCommentsUseCase,
            addCommentUseCase,
            deleteCommentUseCase,
            engagementRepository,
            moderationRepository,
            authRepository,
            ownershipAuthority,
            userRepository,
            deadLetterInteractionDao,
            diagnosticLogger
        )
    }

    @After
    fun tearDown() {
        currentUserFlow.value = null
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
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

    @Test
    fun `reflective prompt should be initialized when artifactId is set`() = runTest {
        val artifactId = "test-artifact-id"
        coEvery { ownershipAuthority.isCurrentUserOwner(artifactId) } returns false
        coEvery { getCommentsUseCase(artifactId, any(), any()) } returns Result.success(
            PaginatedComments(emptyList(), null)
        )
        
        viewModel.initialize(artifactId)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val prompt = viewModel.uiState.value.reflectivePrompt
        org.junit.Assert.assertNotNull("Reflective prompt should not be null", prompt)
        org.junit.Assert.assertTrue("Reflective prompt should not be empty", prompt!!.isNotEmpty())
    }

    @Test
    fun `synced incomplete evidence with authoritative remote locked results in LOCKED`() = runTest {
        val artifactId = "incomplete-artifact"
        val evidence = EngagementEvidence(
            artifactId = artifactId,
            versionTag = "v1",
            durationMs = 10000L,
            syncState = SyncState.SYNCED,
            unlockStatus = UnlockStatus(
                isCommentUnlocked = false,
                isAuthoritative = true
            )
        )
        every { engagementRepository.observeEngagementEvidence(artifactId) } returns flowOf(evidence)

        viewModel.initialize(artifactId)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(CommentUnlockState.LOCKED, viewModel.uiState.value.unlockState)
    }

    @Test
    fun `pending verification before remote result results in VERIFYING`() = runTest {
        val artifactId = "pending-artifact"
        val evidence = EngagementEvidence(
            artifactId = artifactId,
            versionTag = "v1",
            durationMs = 10000L,
            syncState = SyncState.SYNCED,
            unlockStatus = UnlockStatus(
                isCommentUnlocked = false,
                isAuthoritative = false
            )
        )
        every { engagementRepository.observeEngagementEvidence(artifactId) } returns flowOf(evidence)

        viewModel.initialize(artifactId)
        testDispatcher.scheduler.runCurrent()

        assertEquals(CommentUnlockState.VERIFYING, viewModel.uiState.value.unlockState)
    }

    @Test
    fun `authoritative remote unlocked results in UNLOCKED`() = runTest {
        val artifactId = "unlocked-artifact"
        val evidence = EngagementEvidence(
            artifactId = artifactId,
            versionTag = "v1",
            durationMs = 10000L,
            syncState = SyncState.SYNCED,
            unlockStatus = UnlockStatus(
                isCommentUnlocked = true,
                isAuthoritative = true
            )
        )
        every { engagementRepository.observeEngagementEvidence(artifactId) } returns flowOf(evidence)

        viewModel.initialize(artifactId)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(CommentUnlockState.UNLOCKED, viewModel.uiState.value.unlockState)
    }

    @Test
    fun `no 30 second timeout when backend explicitly confirmed locked`() = runTest {
        val artifactId = "explicit-locked-artifact"
        val evidence = EngagementEvidence(
            artifactId = artifactId,
            versionTag = "v1",
            durationMs = 10000L,
            syncState = SyncState.SYNCED,
            unlockStatus = UnlockStatus(
                isCommentUnlocked = false,
                isAuthoritative = true
            )
        )
        every { engagementRepository.observeEngagementEvidence(artifactId) } returns flowOf(evidence)

        viewModel.initialize(artifactId)
        testDispatcher.scheduler.advanceTimeBy(35_000)

        assertEquals(CommentUnlockState.LOCKED, viewModel.uiState.value.unlockState)
    }

    @Test
    fun `initialize with new artifactId loads comments`() = runTest {
        val newArtifactId = "new-artifact-123"
        val comment = Comment(id = "c1", text = "New comment")
        coEvery { getCommentsUseCase(newArtifactId, any(), any()) } returns Result.success(
            PaginatedComments(listOf(comment), null)
        )

        viewModel.initialize(newArtifactId)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.comments.size)
        assertEquals("c1", viewModel.uiState.value.comments[0].id)
        coVerify(exactly = 1) { getCommentsUseCase(newArtifactId, any(), any()) }
    }

    @Test
    fun `initialize with same artifactId without forceRefresh preserves existing behavior`() = runTest {
        val artifactId = "test-artifact"
        val comment = Comment(id = "c1", text = "Existing comment")
        coEvery { getCommentsUseCase(artifactId, any(), any()) } returns Result.success(
            PaginatedComments(listOf(comment), null)
        )

        // Initial load happens in init/initialize
        viewModel.initialize(artifactId)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.comments.size)

        // Call initialize again without forceRefresh (forceRefresh = false)
        viewModel.initialize(artifactId, forceRefresh = false)
        testDispatcher.scheduler.advanceUntilIdle()

        // getCommentsUseCase should not be called again
        coVerify(exactly = 1) { getCommentsUseCase(artifactId, any(), any()) }
    }

    @Test
    fun `initialize with same artifactId and forceRefresh=true invokes getCommentsUseCase again`() = runTest {
        val artifactId = "test-artifact"
        val comment1 = Comment(id = "c1", text = "Comment 1")
        val comment2 = Comment(id = "c2", text = "Comment 2 (new)")

        coEvery { getCommentsUseCase(artifactId, any(), isNull()) } returnsMany listOf(
            Result.success(PaginatedComments(listOf(comment1), null)),
            Result.success(PaginatedComments(listOf(comment1, comment2), null))
        )

        viewModel.initialize(artifactId)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.comments.size)

        // Call initialize with forceRefresh = true
        viewModel.initialize(artifactId, forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.comments.size)
        coVerify(exactly = 2) { getCommentsUseCase(artifactId, any(), isNull()) }
    }

    @Test
    fun `force refresh clears stale empty state`() = runTest {
        val artifactId = "test-artifact"
        val newComment = Comment(id = "c-new", text = "Newly added comment")

        // First load returns empty list
        coEvery { getCommentsUseCase(artifactId, any(), isNull()) } returns Result.success(
            PaginatedComments(emptyList(), null)
        )

        viewModel.initialize(artifactId)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.comments.size)

        // Account B posts a comment in Firestore. Next query returns the new comment.
        coEvery { getCommentsUseCase(artifactId, any(), isNull()) } returns Result.success(
            PaginatedComments(listOf(newComment), null)
        )

        // Account A reopens comments sheet -> forceRefresh = true
        viewModel.initialize(artifactId, forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.comments.size)
        assertEquals("c-new", viewModel.uiState.value.comments[0].id)
    }

    @Test
    fun `refresh does not create duplicate concurrent loads`() = runTest {
        val artifactId = "test-artifact"
        val comment = Comment(id = "c1", text = "Slow loaded comment")

        coEvery { getCommentsUseCase(artifactId, any(), isNull()) } coAnswers {
            delay(1000)
            Result.success(PaginatedComments(listOf(comment), null))
        }

        viewModel.initialize(artifactId, forceRefresh = true)
        testDispatcher.scheduler.advanceTimeBy(200)

        // Re-trigger initialize while first load is in progress
        viewModel.initialize(artifactId, forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        // Only one final result list of 1 comment should exist (no duplicate appended)
        assertEquals(1, viewModel.uiState.value.comments.size)
    }

    @Test
    fun `Firestore failure remains an error rather than becoming No comments yet`() = runTest {
        val artifactId = "test-artifact"
        val firestoreException = Exception("Firestore PERMISSION_DENIED or network failure")

        coEvery { getCommentsUseCase(artifactId, any(), any()) } returns Result.failure(firestoreException)

        viewModel.initialize(artifactId, forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        // State should have an error, and NOT be treated as a successful empty response
        val state = viewModel.uiState.value
        assertEquals(0, state.comments.size)
        Assert.assertNotNull("Error should be set on failure", state.error)
    }

    @Test
    fun `pagination state is correctly reset when a fresh initial load occurs`() = runTest {
        val artifactId = "test-artifact"
        val page1Comment = Comment(id = "c1", text = "Page 1")
        val page2Comment = Comment(id = "c2", text = "Page 2")
        val cursor1 = mockk<DocumentSnapshot>()

        // Page 1
        coEvery { getCommentsUseCase(artifactId, any(), isNull()) } returns Result.success(
            PaginatedComments(listOf(page1Comment), cursor1)
        )
        viewModel.initialize(artifactId)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.hasMorePages)

        // Page 2
        coEvery { getCommentsUseCase(artifactId, any(), cursor1) } returns Result.success(
            PaginatedComments(listOf(page2Comment), null)
        )
        viewModel.loadNextPage()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.comments.size)
        assertEquals(false, viewModel.uiState.value.hasMorePages)

        // Now perform force refresh which resets pagination and re-fetches Page 1
        coEvery { getCommentsUseCase(artifactId, any(), isNull()) } returns Result.success(
            PaginatedComments(listOf(page1Comment), null)
        )
        viewModel.initialize(artifactId, forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        // Comments should be reset back to 1 item (page 1 only), NOT 2 items
        assertEquals(1, viewModel.uiState.value.comments.size)
        assertEquals("c1", viewModel.uiState.value.comments[0].id)
        assertEquals(false, viewModel.uiState.value.hasMorePages)
    }

    @Test
    fun `permanent failure reconciles matching optimistic comment to FAILED status`() = runTest {
        val artifactId = "art-1"
        val commentId = "client-uuid-101"
        val deadLetterFlow = MutableStateFlow<List<DeadLetterInteractionEntity>>(emptyList())
        
        every { authRepository.currentUserId } returns "user-123"
        every { deadLetterInteractionDao.observeDeadLettersForArtifact("art-1", "user-123", InteractionType.COMMENT) } returns deadLetterFlow

        val optimisticComment = Comment(id = commentId, artifactId = artifactId, text = "Optimistic comment")
        coEvery { addCommentUseCase(artifactId, "Optimistic comment") } returns Result.success(optimisticComment)

        viewModel.initialize(artifactId, forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.submitComment("Optimistic comment")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.comments.size)
        assertEquals(CommentStatus.ACTIVE, viewModel.uiState.value.comments[0].status)

        val metadata = json.encodeToString(CommentPayload(id = commentId, artifactId = artifactId))
        val deadLetter = DeadLetterInteractionEntity(
            id = 1L,
            userId = "user-123",
            originalId = 100L,
            artifactId = artifactId,
            interactionType = InteractionType.COMMENT,
            action = "ADD",
            metadata = metadata,
            createdAt = System.currentTimeMillis(),
            correlationId = "corr-1",
            failureReason = "Rate limit exceeded permanently",
            failureType = "PERMANENT",
            retryCount = 3
        )

        val events = mutableListOf<CommentUiEvent>()
        val eventsJob = launch { viewModel.events.collect { events.add(it) } }

        deadLetterFlow.value = listOf(deadLetter)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.comments.size)
        assertEquals(CommentStatus.FAILED, viewModel.uiState.value.comments[0].status)
        assertEquals("Optimistic comment", viewModel.uiState.value.comments[0].text)

        assertEquals(1, events.size)
        val event = events[0] as CommentUiEvent.SubmissionFailed
        assertEquals("Rate limit exceeded permanently", event.error)

        eventsJob.cancel()
    }

    @Test
    fun `dead letter reconciliation affects only matching comment UUID when multiple exist`() = runTest {
        val artifactId = "art-1"
        val commentId1 = "comment-111"
        val commentId2 = "comment-222"
        val deadLetterFlow = MutableStateFlow<List<DeadLetterInteractionEntity>>(emptyList())

        every { authRepository.currentUserId } returns "user-123"
        every { deadLetterInteractionDao.observeDeadLettersForArtifact("art-1", "user-123", InteractionType.COMMENT) } returns deadLetterFlow

        val c1 = Comment(id = commentId1, artifactId = artifactId, text = "First comment")
        val c2 = Comment(id = commentId2, artifactId = artifactId, text = "Second comment")

        coEvery { addCommentUseCase(artifactId, "First comment") } returns Result.success(c1)
        coEvery { addCommentUseCase(artifactId, "Second comment") } returns Result.success(c2)

        viewModel.initialize(artifactId, forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.submitComment("First comment")
        viewModel.submitComment("Second comment")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.comments.size)

        // Only comment 2 failed permanently
        val metadata2 = json.encodeToString(CommentPayload(id = commentId2, artifactId = artifactId))
        val deadLetter = DeadLetterInteractionEntity(
            id = 2L,
            userId = "user-123",
            originalId = 200L,
            artifactId = artifactId,
            interactionType = InteractionType.COMMENT,
            action = "ADD",
            metadata = metadata2,
            createdAt = System.currentTimeMillis(),
            correlationId = "corr-2",
            failureReason = "Backend rejected comment 2",
            failureType = "PERMANENT",
            retryCount = 3
        )

        deadLetterFlow.value = listOf(deadLetter)
        testDispatcher.scheduler.advanceUntilIdle()

        val comments = viewModel.uiState.value.comments
        val comment1 = comments.find { it.id == commentId1 }
        val comment2 = comments.find { it.id == commentId2 }

        Assert.assertNotNull(comment1)
        Assert.assertNotNull(comment2)
        assertEquals(CommentStatus.ACTIVE, comment1?.status)
        assertEquals(CommentStatus.FAILED, comment2?.status)
    }

    @Test
    fun `transient failure in pending state does not mark comment as failed`() = runTest {
        val artifactId = "art-1"
        val commentId = "comment-transient"
        val deadLetterFlow = MutableStateFlow<List<DeadLetterInteractionEntity>>(emptyList())

        every { authRepository.currentUserId } returns "user-123"
        every { deadLetterInteractionDao.observeDeadLettersForArtifact("art-1", "user-123", InteractionType.COMMENT) } returns deadLetterFlow

        val comment = Comment(id = commentId, artifactId = artifactId, text = "Transient comment")
        coEvery { addCommentUseCase(artifactId, "Transient comment") } returns Result.success(comment)

        viewModel.initialize(artifactId, forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.submitComment("Transient comment")
        testDispatcher.scheduler.advanceUntilIdle()

        // No dead letters emitted (it remains in pending queue)
        deadLetterFlow.value = emptyList()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.comments.size)
        assertEquals(CommentStatus.ACTIVE, viewModel.uiState.value.comments[0].status)
    }

    @Test
    fun `successful remote comment remains active and is not marked failed`() = runTest {
        val artifactId = "art-1"
        val comment = Comment(id = "remote-1", artifactId = artifactId, text = "Remote active comment")

        coEvery { getCommentsUseCase(artifactId, any(), isNull()) } returns Result.success(
            PaginatedComments(listOf(comment), null)
        )

        viewModel.initialize(artifactId, forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.comments.size)
        assertEquals(CommentStatus.ACTIVE, viewModel.uiState.value.comments[0].status)
    }

    @Test
    fun `repeated emissions of same dead letter do not produce duplicate events`() = runTest {
        val artifactId = "art-1"
        val commentId = "dup-comment-id"
        val deadLetterFlow = MutableStateFlow<List<DeadLetterInteractionEntity>>(emptyList())

        every { authRepository.currentUserId } returns "user-123"
        every { deadLetterInteractionDao.observeDeadLettersForArtifact("art-1", "user-123", InteractionType.COMMENT) } returns deadLetterFlow

        val comment = Comment(id = commentId, artifactId = artifactId, text = "Duplicate test comment")
        coEvery { addCommentUseCase(artifactId, "Duplicate test comment") } returns Result.success(comment)

        viewModel.initialize(artifactId, forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.submitComment("Duplicate test comment")
        testDispatcher.scheduler.advanceUntilIdle()

        val events = mutableListOf<CommentUiEvent>()
        val eventsJob = launch { viewModel.events.collect { events.add(it) } }

        val metadata = json.encodeToString(CommentPayload(id = commentId, artifactId = artifactId))
        val deadLetter = DeadLetterInteractionEntity(
            id = 55L,
            userId = "user-123",
            originalId = 550L,
            artifactId = artifactId,
            interactionType = InteractionType.COMMENT,
            action = "ADD",
            metadata = metadata,
            createdAt = System.currentTimeMillis(),
            correlationId = "corr-55",
            failureReason = "Permanent failure",
            failureType = "PERMANENT",
            retryCount = 5
        )

        // First emission
        deadLetterFlow.value = listOf(deadLetter)
        testDispatcher.scheduler.advanceUntilIdle()

        // Re-emit exact same list
        deadLetterFlow.value = listOf(deadLetter)
        testDispatcher.scheduler.advanceUntilIdle()

        // Re-emit exact same list again
        deadLetterFlow.value = listOf(deadLetter)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Only 1 event should be emitted despite 3 re-emissions", 1, events.size)
        assertEquals(1, viewModel.uiState.value.comments.size)

        eventsJob.cancel()
    }

    @Test
    fun `observation passes correct artifactId and userId to DAO`() = runTest {
        val artifactId = "isolated-art"
        every { authRepository.currentUserId } returns "user-isolated"

        viewModel.initialize(artifactId, forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        verify {
            deadLetterInteractionDao.observeDeadLettersForArtifact(
                artifactId = "isolated-art",
                userId = "user-isolated",
                interactionType = InteractionType.COMMENT
            )
        }
    }

    @Test
    fun `malformed or unreadable dead letter metadata is handled safely without crash`() = runTest {
        val artifactId = "art-1"
        val commentId = "valid-comment"
        val deadLetterFlow = MutableStateFlow<List<DeadLetterInteractionEntity>>(emptyList())

        every { authRepository.currentUserId } returns "user-123"
        every { deadLetterInteractionDao.observeDeadLettersForArtifact("art-1", "user-123", InteractionType.COMMENT) } returns deadLetterFlow

        val comment = Comment(id = commentId, artifactId = artifactId, text = "Valid comment")
        coEvery { addCommentUseCase(artifactId, "Valid comment") } returns Result.success(comment)

        viewModel.initialize(artifactId, forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.submitComment("Valid comment")
        testDispatcher.scheduler.advanceUntilIdle()

        val malformedDeadLetter = DeadLetterInteractionEntity(
            id = 99L,
            userId = "user-123",
            originalId = 990L,
            artifactId = artifactId,
            interactionType = InteractionType.COMMENT,
            action = "ADD",
            metadata = "{ invalid corrupt json }",
            createdAt = System.currentTimeMillis(),
            correlationId = "corr-99",
            failureReason = "Corrupt metadata",
            failureType = "PERMANENT",
            retryCount = 1
        )

        deadLetterFlow.value = listOf(malformedDeadLetter)
        testDispatcher.scheduler.advanceUntilIdle()

        // Valid comment should NOT be affected or crash
        assertEquals(1, viewModel.uiState.value.comments.size)
        assertEquals(CommentStatus.ACTIVE, viewModel.uiState.value.comments[0].status)
    }

    @Test
    fun `lifecycle safety cancels previous dead letter observation when artifact changes`() = runTest {
        val flow1 = MutableStateFlow<List<DeadLetterInteractionEntity>>(emptyList())
        val flow2 = MutableStateFlow<List<DeadLetterInteractionEntity>>(emptyList())

        every { authRepository.currentUserId } returns "user-123"
        every { deadLetterInteractionDao.observeDeadLettersForArtifact("art-A", "user-123", InteractionType.COMMENT) } returns flow1
        every { deadLetterInteractionDao.observeDeadLettersForArtifact("art-B", "user-123", InteractionType.COMMENT) } returns flow2

        viewModel.initialize("art-A", forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.initialize("art-B", forceRefresh = true)
        testDispatcher.scheduler.advanceUntilIdle()

        verify { deadLetterInteractionDao.observeDeadLettersForArtifact("art-A", "user-123", InteractionType.COMMENT) }
        verify { deadLetterInteractionDao.observeDeadLettersForArtifact("art-B", "user-123", InteractionType.COMMENT) }
    }
}
