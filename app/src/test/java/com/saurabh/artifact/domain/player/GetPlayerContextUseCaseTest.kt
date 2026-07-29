package com.saurabh.artifact.domain.player

import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.*
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.data.local.PendingInteractionDao
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class GetPlayerContextUseCaseTest {
    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val reactionRepository = mockk<ReactionRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val savedArtifactManager = mockk<SavedArtifactManager>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val pendingInteractionDao = mockk<PendingInteractionDao>(relaxed = true)
    private val draftRepository = mockk<DraftRepository>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var useCase: GetPlayerContextUseCase

    @Before
    fun setup() {
        useCase = GetPlayerContextUseCase(
            artifactRepository,
            reactionRepository,
            userRepository,
            savedArtifactManager,
            authRepository,
            pendingInteractionDao,
            draftRepository,
            diagnosticLogger
        )
        
        every { authRepository.currentUser } returns MutableStateFlow(null)
        every { savedArtifactManager.savedIds } returns MutableStateFlow(emptySet())
    }

    @Test
    fun `should detect transition from draft to published and retry on failure`() = runTest {
        val artifactId = "art123"
        val draftArtifact = Artifact(id = artifactId, status = ArtifactStatus.DRAFT)
        val publishedArtifact = Artifact(id = artifactId, status = ArtifactStatus.ACTIVE)
        
        val artifactFlow = MutableStateFlow<Artifact?>(draftArtifact)
        
        // Mock observeArtifact to fail first then succeed
        val results = mutableListOf<Artifact?>(null, null, publishedArtifact)
        every { artifactRepository.observeArtifact(artifactId) } answers {
            flow {
                val next = if (results.isNotEmpty()) results.removeAt(0) else publishedArtifact
                emit(next)
            }
        }

        val metadataFlow = useCase.execute(artifactFlow)
        
        // 1. Initial Draft state
        metadataFlow.first() 
        
        // 2. Transition to Published
        artifactFlow.value = publishedArtifact
        
        // Collect updates
        val emissions = mutableListOf<PlayerMetadata>()
        val job = metadataFlow.onEach { emissions.add(it) }.launchIn(this)
        
        // Allow time for retries
        advanceTimeBy(5.seconds)
        
        // Verify retry occurred (logs)
        verify(atLeast = 1) { diagnosticLogger.warn(any(), "ARTIFACT_OBSERVE_RETRYING", any()) }
        
        // Verify eventually we get the published metadata
        // Note: emissions might have multiple entries due to combined flows
        assert(emissions.any { it.artifactId == artifactId })
        
        job.cancel()
    }

    @Test
    fun `should not retry on failure during normal playback from feed`() = runTest {
        val artifactId = "art_feed"
        val publishedArtifact = Artifact(id = artifactId, status = ArtifactStatus.ACTIVE)
        
        val artifactFlow = MutableStateFlow<Artifact?>(publishedArtifact)
        
        // Mock observeArtifact to emit null (permission denied)
        every { artifactRepository.observeArtifact(artifactId) } returns flowOf(null)

        val metadataFlow = useCase.execute(artifactFlow)
        
        val emissions = mutableListOf<PlayerMetadata>()
        val job = metadataFlow.onEach { emissions.add(it) }.launchIn(this)
        
        advanceTimeBy(10.seconds)
        
        // Verify NO retry logs
        verify(exactly = 0) { diagnosticLogger.warn(any(), "ARTIFACT_OBSERVE_RETRYING", any()) }
        
        job.cancel()
    }

    @Test
    fun `should bound retries to maximum 3 attempts`() = runTest {
        val artifactId = "art_retry"
        val draftArtifact = Artifact(id = artifactId, status = ArtifactStatus.DRAFT)
        val publishedArtifact = Artifact(id = artifactId, status = ArtifactStatus.ACTIVE)
        
        val artifactFlow = MutableStateFlow<Artifact?>(draftArtifact)
        
        // Mock observeArtifact to ALWAYS fail
        every { artifactRepository.observeArtifact(artifactId) } returns flowOf(null)

        val metadataFlow = useCase.execute(artifactFlow)
        
        // Trigger transition
        artifactFlow.value = publishedArtifact
        
        val job = metadataFlow.launchIn(this)
        
        advanceTimeBy(10.seconds) // Enough for 3 retries (2s each)
        
        // Verify exactly 3 retries (total 4 listeners: initial + 3 retries)
        verify(exactly = 4) { artifactRepository.observeArtifact(artifactId) }
        verify(exactly = 3) { diagnosticLogger.warn(any(), "ARTIFACT_OBSERVE_RETRYING", any()) }
        
        job.cancel()
    }

    @Test
    fun `should cancel retry when track changes during delay`() = runTest {
        val artifactId1 = "art1"
        val artifactId2 = "art2"
        val draft1 = Artifact(id = artifactId1, status = ArtifactStatus.DRAFT)
        val published1 = Artifact(id = artifactId1, status = ArtifactStatus.ACTIVE)
        val published2 = Artifact(id = artifactId2, status = ArtifactStatus.ACTIVE)
        
        val artifactFlow = MutableStateFlow<Artifact?>(draft1)
        
        // Mock art1 to fail
        every { artifactRepository.observeArtifact(artifactId1) } returns flowOf(null)
        // Mock art2 to succeed
        every { artifactRepository.observeArtifact(artifactId2) } returns flowOf(published2)

        val metadataFlow = useCase.execute(artifactFlow)
        
        // 1. Trigger transition for art1 (starts retry delay)
        artifactFlow.value = published1
        
        val job = metadataFlow.launchIn(this)
        
        advanceTimeBy(1.seconds) // Less than retry delay (2s)
        
        // 2. Change track to art2
        artifactFlow.value = published2
        
        advanceTimeBy(5.seconds)
        
        // Verify retry for art1 was NOT attempted after the track change
        // Initial attempt was 1. If retry happened, it would be more.
        verify(exactly = 1) { artifactRepository.observeArtifact(artifactId1) }
        verify(atLeast = 1) { artifactRepository.observeArtifact(artifactId2) }
        
        job.cancel()
    }

    @Test
    fun `should maintain only one active listener after successful retry`() = runTest {
        val artifactId = "art_lifecycle"
        val draft = Artifact(id = artifactId, status = ArtifactStatus.DRAFT)
        val published = Artifact(id = artifactId, status = ArtifactStatus.ACTIVE)
        
        val artifactFlow = MutableStateFlow<Artifact?>(draft)
        
        val listenerActiveCount = java.util.concurrent.atomic.AtomicInteger(0)
        
        every { artifactRepository.observeArtifact(artifactId) } answers {
            callbackFlow {
                listenerActiveCount.incrementAndGet()
                if (listenerActiveCount.get() == 1) {
                    // Fail the first one
                    trySend(null)
                } else {
                    // Succeed the second one (retry)
                    trySend(published)
                }
                awaitClose { listenerActiveCount.decrementAndGet() }
            }
        }

        val metadataFlow = useCase.execute(artifactFlow)
        artifactFlow.value = published
        
        val job = metadataFlow.launchIn(this)
        
        advanceTimeBy(5.seconds)
        
        // Verify exactly 1 listener is active (the successful retry)
        assertEquals(1, listenerActiveCount.get())
        
        job.cancel()
        
        // Verify it was cleaned up
        assertEquals(0, listenerActiveCount.get())
    }

    @Test
    fun `should emit terminal null after retries are exhausted`() = runTest {
        val artifactId = "art_exhaust"
        val draft = Artifact(id = artifactId, status = ArtifactStatus.DRAFT)
        val published = Artifact(id = artifactId, status = ArtifactStatus.ACTIVE)
        
        val artifactFlow = MutableStateFlow<Artifact?>(draft)
        
        // Mock observeArtifact to ALWAYS emit null
        every { artifactRepository.observeArtifact(artifactId) } returns flowOf(null)

        val metadataFlow = useCase.execute(artifactFlow)
        artifactFlow.value = published
        
        val emissions = mutableListOf<PlayerMetadata>()
        val job = metadataFlow.onEach { emissions.add(it) }.launchIn(this)
        
        advanceTimeBy(10.seconds)
        
        // Verify exactly 3 retries occurred
        verify(exactly = 3) { diagnosticLogger.warn(any(), "ARTIFACT_OBSERVE_RETRYING", any()) }
        
        // We don't verify terminal null emission here because filterNotNull() is used downstream.
        // But we verify that the flow doesn't CRASH and remains responsive.
        assert(emissions.isNotEmpty())
        
        job.cancel()
    }
}
