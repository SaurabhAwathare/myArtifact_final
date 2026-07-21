package com.saurabh.artifact.repository

import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.data.mapper.DraftToArtifactMapper
import com.saurabh.artifact.model.*
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DraftRepositoryOptimizationTest {
    private val draftDao = mockk<DraftDao>()
    private val uploadTaskDao = mockk<UploadTaskDao>()
    private val draftsDatabase = mockk<AppDatabase>()
    private val draftToArtifactMapper = mockk<DraftToArtifactMapper>()
    private val userRepository = mockk<UserRepository>()

    private val repository = DraftRepository(
        draftDao = draftDao,
        uploadTaskDao = uploadTaskDao,
        draftsDatabase = draftsDatabase,
        draftToArtifactMapper = draftToArtifactMapper,
        userRepository = userRepository
    )

    @Test
    fun `observeDraftAsArtifact should suppress emissions when only progress changes`() = runTest {
        val draftId = "test_draft"
        val userId = "test_user"
        val user = mockk<User>(relaxed = true) { every { id } returns userId }
        
        val baseDraft = ArtifactDraftEntity(
            id = draftId,
            localAudioPath = "/audio/path",
            title = "Original Title",
            uploadedBytes = 0,
            totalBytes = 1000,
            updatedAt = 100L
        )
        
        val progressDraft = baseDraft.copy(
            uploadedBytes = 500,
            updatedAt = 200L
        )
        
        val structuralChangeDraft = baseDraft.copy(
            title = "New Title",
            updatedAt = 300L
        )

        every { userRepository.getCurrentUserId() } returns userId
        every { userRepository.streamUserProfile(userId) } returns flowOf(user)
        every { draftDao.observeDraftById(draftId) } returns flowOf(baseDraft, progressDraft, structuralChangeDraft)
        
        // Return dummy artifact
        every { draftToArtifactMapper.map(any(), any(), any()) } returns mockk<Artifact>(relaxed = true)

        val emissions = mutableListOf<Artifact?>()
        val job = launch(UnconfinedTestDispatcher()) {
            repository.observeDraftAsArtifact(draftId).toList(emissions)
        }

        // Expected: baseDraft (initial), then structuralChangeDraft. 
        // progressDraft should be filtered out because title, id, etc didn't change.
        assertEquals(2, emissions.size)
        
        verify(exactly = 2) { draftToArtifactMapper.map(any(), any(), any()) }
        
        job.cancel()
    }
}
