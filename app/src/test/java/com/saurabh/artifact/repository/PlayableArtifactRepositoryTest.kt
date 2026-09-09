package com.saurabh.artifact.repository

import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.mapper.DraftToArtifactMapper
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.domain.SafetyPolicy
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.ModerationMetadata
import com.saurabh.artifact.model.ModerationStatus
import com.saurabh.artifact.model.PlaybackSource
import com.saurabh.artifact.model.User
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlayableArtifactRepositoryTest {
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val draftToArtifactMapper = mockk<DraftToArtifactMapper>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private lateinit var repository: PlayableArtifactRepository

    private companion object {
        private const val TEST_USER_ID = "test-user-id"
    }

    @Before
    fun setup() {
        every { userRepository.getCurrentUserId() } returns TEST_USER_ID
        coEvery { userRepository.getCachedProfile() } returns User(
            id = TEST_USER_ID, 
            anonymousId = "usr_123", 
            anonymousName = "Tester", 
            anonymousSigil = "T1", 
            sigilSeed = "seed123"
        )
        every { draftToArtifactMapper.map(any(), any(), any()) } answers {
            val draft = it.invocation.args[0] as ArtifactDraftEntity
            Artifact(id = draft.id, userId = draft.userId, audioUrl = draft.localAudioPath, isDraftField = true)
        }
        repository = PlayableArtifactRepository(
            Lazy { draftDao },
            artifactRepository,
            draftToArtifactMapper,
            userRepository,
            SafetyPolicy(),
            diagnosticLogger
        )
    }

    @Test
    fun `resolveArtifact should return draft if it exists and lifecycle is not PUBLISHED`() = runBlocking {
        val id = "draft123"
        val draft = ArtifactDraftEntity(
            id = id, 
            userId = TEST_USER_ID, 
            localAudioPath = "/path/to/audio",
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH
        )
        coEvery { draftDao.getDraftById(id, any()) } returns draft

        val result = repository.resolveArtifact(id, PlaybackSource.FEED_PLAYBACK)

        if (result.isFailure) {
            throw AssertionError("Expected success but failed with: ${result.exceptionOrNull()}", result.exceptionOrNull())
        }
        val playable = result.getOrThrow()
        assertEquals(id, playable.id)
        assertNotNull(playable.originalDraft)
    }

    @Test
    fun `resolveArtifact should ignore draft if lifecycle is PUBLISHED and resolve published artifact`() = runBlocking {
        val id = "published123"
        val publishedDraftRecord = ArtifactDraftEntity(
            id = id, 
            userId = TEST_USER_ID, 
            localAudioPath = "/path/to/audio",
            lifecycle = ArtifactLifecycle.PUBLISHED
        )
        val publishedArtifact = Artifact(
            id = id,
            userId = TEST_USER_ID,
            status = ArtifactStatus.ACTIVE,
            audioUrl = "https://firebasestorage.googleapis.com/audio.mp3"
        )

        coEvery { draftDao.getDraftById(id, any()) } returns publishedDraftRecord
        coEvery { artifactRepository.getArtifactById(id) } returns Result.success(publishedArtifact)

        val result = repository.resolveArtifact(id, PlaybackSource.FEED_PLAYBACK)

        assertTrue(result.isSuccess)
        val playable = result.getOrThrow()
        assertEquals(id, playable.id)
        assertNull("Published artifact must NOT be treated as a draft", playable.originalDraft)
        assertNotNull("Published artifact must resolve originalArtifact", playable.originalArtifact)
        assertEquals(publishedArtifact.audioUrl, playable.audioUrl)
    }

    @Test
    fun `resolveArtifact should return failure if artifact is DELETED`() = runBlocking {
        val id = "deleted123"
        val artifact = Artifact(id = id, status = ArtifactStatus.DELETED)
        
        coEvery { draftDao.getDraftById(id, any()) } returns null
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
        
        coEvery { draftDao.getDraftById(id, any()) } returns null
        coEvery { artifactRepository.getArtifactById(id) } returns Result.success(artifact)

        val result = repository.resolveArtifact(id, PlaybackSource.FEED_PLAYBACK)

        assertTrue(result.isSuccess)
        val playable = result.getOrThrow()
        assertEquals(id, playable.id)
    }

    @Test
    fun `resolveArtifact should return failure if artifact is HIDDEN by moderation`() = runBlocking {
        val id = "hidden123"
        val artifact = Artifact(
            id = id, 
            status = ArtifactStatus.ACTIVE,
            moderation = ModerationMetadata(status = ModerationStatus.HIDDEN)
        )
        
        coEvery { draftDao.getDraftById(id, any()) } returns null
        coEvery { artifactRepository.getArtifactById(id) } returns Result.success(artifact)

        val result = repository.resolveArtifact(id, PlaybackSource.DEEP_LINK)

        assertTrue("Should fail for hidden artifact", result.isFailure)
        assertTrue(result.exceptionOrNull() is AppError.NotFound)
    }

    @Test
    fun `resolveArtifactsByIds should ignore PUBLISHED draft records`() = runBlocking {
        val draftId = "unfinished_draft"
        val publishedId = "published_artifact"

        val draftEntity = ArtifactDraftEntity(id = draftId, userId = TEST_USER_ID, localAudioPath = "/path/1", lifecycle = ArtifactLifecycle.READY_TO_PUBLISH)
        val publishedDraftEntity = ArtifactDraftEntity(id = publishedId, userId = TEST_USER_ID, localAudioPath = "/path/2", lifecycle = ArtifactLifecycle.PUBLISHED)
        val publishedRemoteArtifact = Artifact(id = publishedId, userId = TEST_USER_ID, status = ArtifactStatus.ACTIVE)

        coEvery { draftDao.getDraftById(draftId, any()) } returns draftEntity
        coEvery { draftDao.getDraftById(publishedId, any()) } returns publishedDraftEntity
        coEvery { artifactRepository.getArtifactsByIds(listOf(publishedId)) } returns Result.success(listOf(publishedRemoteArtifact))

        val result = repository.resolveArtifactsByIds(listOf(draftId, publishedId))

        if (result.isFailure) {
            throw AssertionError("Expected success but failed with: ${result.exceptionOrNull()}", result.exceptionOrNull())
        }
        val list = result.getOrThrow()
        assertEquals(2, list.size)
        assertTrue("First item should be mapped draft", list[0].isDraft)
        assertTrue("Second item should be remote published artifact", !list[1].isDraft)
    }
}
