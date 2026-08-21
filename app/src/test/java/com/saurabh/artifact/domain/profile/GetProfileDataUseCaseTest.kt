package com.saurabh.artifact.domain.profile

import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.*
import com.saurabh.artifact.domain.ArtifactVisibilityFilter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
    private val visibilityFilter = mockk<ArtifactVisibilityFilter>()

    private lateinit var useCase: GetProfileDataUseCase

    @Before
    fun setup() {
        useCase = GetProfileDataUseCase(
            userRepository,
            artifactRepository,
            recordingRepository,
            authRepository,
            visibilityFilter
        )
        
        val mockUser = mockk<com.google.firebase.auth.FirebaseUser> {
            every { uid } returns "user123"
        }
        every { authRepository.currentUserId } returns "user123"
        every { authRepository.currentUser } returns kotlinx.coroutines.flow.MutableStateFlow(mockUser)
        every { userRepository.streamUserProfile(any()) } returns flowOf(null)
        every { userRepository.observeIsResonating(any(), any()) } returns flowOf(false)
        every { artifactRepository.getSavedArtifacts(any()) } returns flowOf(emptyList())
        every { visibilityFilter.observeSuppressedIds(any()) } returns flowOf(emptySet())
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

    @Test
    fun `should filter reported artifacts reactively`() = runTest {
        val artifact1 = mockk<Artifact> {
            every { id } returns "art1"
            every { status } returns ArtifactStatus.ACTIVE
        }
        val artifact2 = mockk<Artifact> {
            every { id } returns "art2"
            every { status } returns ArtifactStatus.ACTIVE
        }
        
        every { artifactRepository.getUserArtifacts(any(), any()) } returns flowOf(listOf(artifact1, artifact2) to null)
        every { recordingRepository.observeDrafts() } returns flowOf(emptyList())
        
        val suppressedFlow = kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())
        every { visibilityFilter.observeSuppressedIds(any()) } returns suppressedFlow

        val results = mutableListOf<ProfileData?>()
        val job = launch {
            useCase(null).collect { results.add(it) }
        }

        // Initially both visible
        assertEquals(1, results.size)
        assertEquals(2, results.last()?.publishedArtifacts?.size)

        // Report art1
        suppressedFlow.value = setOf("art1")
        assertEquals(2, results.size)
        assertEquals(1, results.last()?.publishedArtifacts?.size)
        assertEquals("art2", results.last()?.publishedArtifacts?.get(0)?.id)

        job.cancel()
    }
}
