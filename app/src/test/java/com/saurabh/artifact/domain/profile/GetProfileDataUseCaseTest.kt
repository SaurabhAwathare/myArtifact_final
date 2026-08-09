package com.saurabh.artifact.domain.profile

import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Date

class GetProfileDataUseCaseTest {

    private val userRepository = mockk<UserRepository>()
    private val artifactRepository = mockk<ArtifactRepository>()
    private val recordingRepository = mockk<RecordingRepository>()
    private val authRepository = mockk<AuthRepository>()

    private lateinit var useCase: GetProfileDataUseCase

    @Before
    fun setup() {
        useCase = GetProfileDataUseCase(
            userRepository,
            artifactRepository,
            recordingRepository,
            authRepository
        )
        
        val mockUser = mockk<com.google.firebase.auth.FirebaseUser> {
            every { uid } returns "user123"
        }
        every { authRepository.currentUserId } returns "user123"
        every { authRepository.currentUser } returns kotlinx.coroutines.flow.MutableStateFlow(mockUser)
        every { userRepository.streamUserProfile(any()) } returns flowOf(null)
        every { userRepository.observeIsResonating(any(), any()) } returns flowOf(false)
        every { artifactRepository.getSavedArtifacts(any()) } returns flowOf(emptyList())
    }

    @Test
    fun `should deduplicate cloud drafts when local draft with same ID exists`() = runTest {
        val artifactId = "collision_id"
        
        // 1. Mock local draft in Room
        val localDraft = mockk<ArtifactDraftEntity> {
            every { id } returns artifactId
            every { lifecycle } returns ArtifactLifecycle.PROCESSING
        }
        every { recordingRepository.observeDrafts() } returns flowOf(listOf(localDraft))

        // 2. Mock cloud artifact in Firestore with same ID (but not ACTIVE yet)
        val cloudArtifact = mockk<Artifact> {
            every { id } returns artifactId
            every { status } returns ArtifactStatus.PENDING_UPLOAD
        }
        every { artifactRepository.getUserArtifacts("user123", any()) } returns flowOf(listOf(cloudArtifact) to null)

        val result = useCase(null).first()

        assertNotNull(result)
        assertEquals(1, result!!.localDrafts.size)
        assertEquals(artifactId, result.localDrafts[0].id)
        
        // Verification: cloudDrafts should be empty because 'artifactId' is already in localDrafts
        assertTrue("cloudDrafts should be empty due to deduplication", result.cloudDrafts.isEmpty())
    }

    @Test
    fun `should show cloud drafts when no matching local draft exists`() = runTest {
        every { recordingRepository.observeDrafts() } returns flowOf(emptyList())

        val cloudArtifact = mockk<Artifact> {
            every { id } returns "unique_cloud_id"
            every { status } returns ArtifactStatus.PENDING_UPLOAD
        }
        every { artifactRepository.getUserArtifacts("user123", any()) } returns flowOf(listOf(cloudArtifact) to null)

        val result = useCase(null).first()

        assertNotNull(result)
        assertTrue(result!!.localDrafts.isEmpty())
        assertEquals(1, result.cloudDrafts.size)
        assertEquals("unique_cloud_id", result.cloudDrafts[0].id)
    }
}
