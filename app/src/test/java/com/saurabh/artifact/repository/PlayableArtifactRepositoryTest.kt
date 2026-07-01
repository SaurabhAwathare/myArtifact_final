package com.saurabh.artifact.repository

import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.PlaybackSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlayableArtifactRepositoryTest {
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private lateinit var repository: PlayableArtifactRepository

    @Before
    fun setup() {
        repository = PlayableArtifactRepository(draftDao, artifactRepository)
    }

    @Test
    fun `resolveArtifact should return draft if it exists`() = runBlocking {
        val id = "draft123"
        val draft = ArtifactDraftEntity(id = id, localAudioPath = "/path/to/audio")
        coEvery { draftDao.getDraftById(id) } returns draft

        val result = repository.resolveArtifact(id, PlaybackSource.FEED_PLAYBACK)

        assertTrue(result.isSuccess)
        val playable = result.getOrThrow()
        assertTrue(playable.id == id)
        assertNotNull(playable.originalDraft)
    }

    @Test
    fun `resolveArtifact should return failure if artifact is DELETED`() = runBlocking {
        val id = "deleted123"
        val artifact = Artifact(id = id, status = ArtifactStatus.DELETED)
        
        coEvery { draftDao.getDraftById(id) } returns null
        coEvery { artifactRepository.getArtifactById(id) } returns Result.success(artifact)

        val result = repository.resolveArtifact(id, PlaybackSource.FEED_PLAYBACK)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is AppError.NotFound)
    }

    @Test
    fun `resolveArtifact should return success if artifact is ACTIVE`() = runBlocking {
        val id = "active123"
        val artifact = Artifact(id = id, status = ArtifactStatus.ACTIVE)
        
        coEvery { draftDao.getDraftById(id) } returns null
        coEvery { artifactRepository.getArtifactById(id) } returns Result.success(artifact)

        val result = repository.resolveArtifact(id, PlaybackSource.FEED_PLAYBACK)

        assertTrue(result.isSuccess)
        val playable = result.getOrThrow()
        assertTrue(playable.id == id)
    }
}
