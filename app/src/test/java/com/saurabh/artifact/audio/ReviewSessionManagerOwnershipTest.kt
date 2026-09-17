package com.saurabh.artifact.audio

import com.saurabh.artifact.audio.validation.ReviewProgress
import com.saurabh.artifact.audio.validation.ReviewResult
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.mapper.DraftToArtifactMapper
import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.domain.review.publishing.PublishingReviewPolicy
import com.saurabh.artifact.domain.review.publishing.PublishingReviewValidator
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.UserRepository
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewSessionManagerOwnershipTest {

    private val playbackSessionManager = mockk<PlaybackSessionManager>(relaxed = true)
    private val reviewAuthorityService = mockk<ReviewAuthorityService>(relaxed = true)
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val publishingValidator = mockk<PublishingReviewValidator>(relaxed = true)
    private val publishingPolicy = mockk<PublishingReviewPolicy>(relaxed = true)
    private val draftMapper = mockk<DraftToArtifactMapper>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)

    private lateinit var manager: ReviewSessionManager

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { reviewAuthorityService.currentProgress } returns MutableStateFlow(null)
        
        manager = ReviewSessionManager(
            playbackSessionManager = playbackSessionManager,
            reviewAuthorityService = reviewAuthorityService,
            draftDao = { draftDao },
            publishingValidator = publishingValidator,
            publishingPolicy = publishingPolicy,
            draftMapper = draftMapper,
            userRepository = userRepository,
        )
    }

    @After
    fun tearDown() {
        if (::manager.isInitialized) {
            manager.cancelScope()
        }
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun ReviewSessionManager.cancelScope() {
        runCatching {
            val field = ReviewSessionManager::class.java.getDeclaredField("scope")
            field.isAccessible = true
            (field.get(this) as? CoroutineScope)?.cancel()
        }
    }

    @Test
    fun `startReview should only play if draft belongs to current user`() = runTest(testDispatcher) {
        try {
            val draftId = "draft_123"
            val currentUserId = "user_A"

            every { userRepository.getCurrentUserId() } returns currentUserId
            coEvery { userRepository.getCachedProfile() } returns null
            
            // Scenario 1: Draft belongs to current user
            val myDraft = mockk<ArtifactDraftEntity>(relaxed = true) {
                every { id } returns draftId
                every { userId } returns currentUserId
            }
            coEvery { draftDao.getDraftById(draftId, currentUserId) } returns myDraft
            
            manager.startReview(draftId)
            
            coVerify(exactly = 1) { playbackSessionManager.play(any(), any(), any(), any(), any()) }

            // Scenario 2: Draft belongs to another user (or not found for current user)
            coEvery { draftDao.getDraftById(draftId, currentUserId) } returns null
            
            manager.startReview(draftId)
            
            // Should not have called play again
            coVerify(exactly = 1) { playbackSessionManager.play(any(), any(), any(), any(), any()) }
        } finally {
            manager.cancelScope()
        }
    }

    @Test
    fun `markReviewComplete logic check - use current user ID`() = runTest(testDispatcher) {
        try {
            val draftId = "draft_123"
            val currentUserId = "user_A"
            
            every { userRepository.getCurrentUserId() } returns currentUserId
            
            val progressFlow = MutableStateFlow<ReviewProgress?>(null)
            every { reviewAuthorityService.currentProgress } returns progressFlow
            
            // Re-init manager to pick up the mocked flow
            if (::manager.isInitialized) {
                manager.cancelScope()
            }
            manager = ReviewSessionManager(
                playbackSessionManager = playbackSessionManager,
                reviewAuthorityService = reviewAuthorityService,
                draftDao = { draftDao },
                publishingValidator = publishingValidator,
                publishingPolicy = publishingPolicy,
                draftMapper = draftMapper,
                userRepository = userRepository,
            )

            // Mock validation result: Threshold met
            val mockEvidence = mockk<EngagementEvidence>(relaxed = true) {
                every { artifactId } returns draftId
            }
            val mockProgress = mockk<ReviewProgress>(relaxed = true) {
                every { artifactId } returns draftId
                every { evidence } returns mockEvidence
            }
            every { publishingValidator.validate(any(), any()) } returns ReviewResult(isValid = true, coveragePercent = 1.0f, reachedEnd = true)

            // Mock draft lookup for the update
            val myDraft = mockk<ArtifactDraftEntity>(relaxed = true) {
                every { id } returns draftId
                every { userId } returns currentUserId
                every { lifecycle } returns ArtifactLifecycle.REVIEW_REQUIRED
                every { reviewCompleted } returns false
            }
            coEvery { draftDao.getDraftById(draftId, currentUserId) } returns myDraft

            // Trigger the flow
            progressFlow.value = mockProgress
            
            // Wait for the asynchronous markReviewComplete to run on Dispatchers.IO
            coVerify(timeout = 3000) { draftDao.markReviewCompletePartial(draftId, currentUserId) }
            
            // Verify no agnostic lookups were used in this path
            coVerify(exactly = 0) { draftDao.internalGetDraftByIdAgnostic(any()) }
        } finally {
            manager.cancelScope()
        }
    }
}
