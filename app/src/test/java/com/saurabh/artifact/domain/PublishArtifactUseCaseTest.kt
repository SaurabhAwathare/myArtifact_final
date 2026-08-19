package com.saurabh.artifact.domain

import com.saurabh.artifact.domain.review.publishing.PublishingReviewPolicy
import com.saurabh.artifact.model.PublishingResult
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.RecordingRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        useCase = PublishArtifactUseCase(recordingRepository, authRepository, publishingOrchestrator, publishingPolicy)
        every { authRepository.currentUserId } returns "user123"
    }

    @Test
    fun `invoke should trigger publishing orchestrator`() = runBlocking {
        val draftId = "draft123"
        val draftFilePath = "path/to/draft"
        val expectedResult = PublishingResult.UPLOAD_STARTED
        
        val draft = mockk<com.saurabh.artifact.data.local.ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
            every { lifecycle } returns com.saurabh.artifact.model.ArtifactLifecycle.READY_TO_PUBLISH
            every { title } returns "Valid Title"
        }
        coEvery { recordingRepository.getDraftByPath(draftFilePath) } returns Result.success(draft)
        coEvery { publishingOrchestrator.approvePublishing(draftId) } returns expectedResult
        
        val result = useCase(draftFilePath)
        
        assert(result.isSuccess)
        assertEquals(expectedResult, result.getOrNull())
        coVerify { publishingOrchestrator.approvePublishing(draftId) }
    }

    @Test
    fun `invoke should fail if title exceeds 70 characters`() = runBlocking {
        val draftId = "draft123"
        val draftFilePath = "path/to/draft"
        
        val draft = mockk<com.saurabh.artifact.data.local.ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
            every { lifecycle } returns com.saurabh.artifact.model.ArtifactLifecycle.READY_TO_PUBLISH
            every { title } returns "A".repeat(71)
        }
        coEvery { recordingRepository.getDraftByPath(draftFilePath) } returns Result.success(draft)
        
        val result = useCase(draftFilePath)
        
        assert(result.isFailure)
        assertEquals("Title must not exceed 70 characters", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { publishingOrchestrator.approvePublishing(any()) }
    }
}
