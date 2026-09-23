package com.saurabh.artifact.domain

import android.util.Log
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.domain.review.publishing.PublishingReviewPolicy
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.PublishingResult
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.RecordingRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PublishArtifactUseCaseTest {
    private val recordingRepository = mockk<RecordingRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val publishingOrchestrator = mockk<PublishingOrchestrator>(relaxed = true)
    private val publishingPolicy = mockk<PublishingReviewPolicy>(relaxed = true)
    
    private lateinit var useCase: PublishArtifactUseCase

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        useCase = PublishArtifactUseCase(recordingRepository, authRepository, publishingOrchestrator, publishingPolicy)
        every { authRepository.currentUserId } returns "user123"
        every { publishingPolicy.minCoverage } returns 0.5f
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `TEST 1 - receives draftId and resolves draft even when localAudioPath changed`() = runBlocking {
        val draftId = "draft123"
        val expectedResult = PublishingResult.UPLOAD_STARTED
        
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
            every { localAudioPath } returns "/new/transcoded/path/audio.m4a"
            every { lifecycle } returns ArtifactLifecycle.READY_TO_PUBLISH
            every { title } returns "Valid Title"
        }
        coEvery { recordingRepository.getDraft(draftId) } returns Result.success(draft)
        coEvery { publishingOrchestrator.approvePublishing(draftId) } returns Result.success(expectedResult)
        
        val result = useCase(draftId)
        
        assertTrue(result.isSuccess)
        assertEquals(expectedResult, result.getOrNull())
        coVerify { recordingRepository.getDraft(draftId) }
        coVerify { publishingOrchestrator.approvePublishing(draftId) }
    }

    @Test
    fun `TEST 2 - publishing no longer calls getDraftByPath`() = runBlocking {
        val draftId = "draft123"
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
            every { lifecycle } returns ArtifactLifecycle.READY_TO_PUBLISH
            every { title } returns "Valid Title"
        }
        coEvery { recordingRepository.getDraft(draftId) } returns Result.success(draft)
        coEvery { publishingOrchestrator.approvePublishing(draftId) } returns Result.success(PublishingResult.UPLOAD_STARTED)

        useCase(draftId)

        coVerify(exactly = 0) { recordingRepository.getDraftByPath(any()) }
    }

    @Test
    fun `TEST 7 - draft not in READY_TO_PUBLISH is rejected`() = runBlocking {
        val draftId = "draft123"
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
            every { lifecycle } returns ArtifactLifecycle.REVIEW_REQUIRED
            every { title } returns "Valid Title"
        }
        coEvery { recordingRepository.getDraft(draftId) } returns Result.success(draft)

        val result = useCase(draftId)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AppError.InvalidInput)
        coVerify(exactly = 0) { publishingOrchestrator.approvePublishing(any()) }
    }

    @Test
    fun `TEST 9 - draft not found returns NotFound error`() = runBlocking {
        val draftId = "non_existent_draft"
        coEvery { recordingRepository.getDraft(draftId) } returns Result.failure(AppError.NotFound("Draft", draftId))

        val result = useCase(draftId)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AppError.NotFound)
        coVerify(exactly = 0) { publishingOrchestrator.approvePublishing(any()) }
    }

    @Test
    fun `invoke should fail if title exceeds 70 characters`() = runBlocking {
        val draftId = "draft123"
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
            every { lifecycle } returns ArtifactLifecycle.READY_TO_PUBLISH
            every { title } returns "A".repeat(71)
        }
        coEvery { recordingRepository.getDraft(draftId) } returns Result.success(draft)
        
        val result = useCase(draftId)
        
        assertTrue(result.isFailure)
        assertEquals("Invalid input: Title must not exceed 70 characters", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { publishingOrchestrator.approvePublishing(any()) }
    }

    @Test
    fun `invoke should propagate orchestrator failure`() = runBlocking {
        val draftId = "draft123"
        val errorMessage = "Validation failed: Title contains PII"
        
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
            every { lifecycle } returns ArtifactLifecycle.READY_TO_PUBLISH
            every { title } returns "Valid Title"
        }
        coEvery { recordingRepository.getDraft(draftId) } returns Result.success(draft)
        coEvery { publishingOrchestrator.approvePublishing(draftId) } returns Result.failure(AppError.InvalidInput(errorMessage))
        
        val result = useCase(draftId)
        
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is AppError.InvalidInput)
        assertEquals("Invalid input: $errorMessage", exception?.message)
    }
}
