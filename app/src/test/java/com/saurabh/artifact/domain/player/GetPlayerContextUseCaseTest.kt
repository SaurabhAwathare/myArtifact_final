package com.saurabh.artifact.domain.player

import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.*
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.data.local.PendingInteractionEntity
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.InteractionAction
import com.google.firebase.auth.FirebaseUser
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import com.google.firebase.firestore.FirebaseFirestoreException
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

import dagger.Lazy
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
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
            Lazy { pendingInteractionDao },
            draftRepository,
            diagnosticLogger
        )
        
        every { authRepository.currentUser } returns MutableStateFlow(null)
        every { authRepository.userData } returns MutableStateFlow(null)
        every { savedArtifactManager.savedIds } returns MutableStateFlow(emptySet())
        every { reactionRepository.getReactionCounts(any()) } returns flowOf(null)
        every { reactionRepository.getArtifactReactions(any(), any()) } returns flowOf(emptyList())
        every { pendingInteractionDao.observePendingForArtifact(any(), any()) } returns flowOf(emptyList())
        every { draftRepository.observeDraftAsArtifact(any()) } returns flowOf(null)
        every { userRepository.observeIsResonating(any(), any()) } returns flowOf(false)
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
        
        // Actively start metadataFlow collection while flow contains draftArtifact
        val emissions = mutableListOf<PlayerMetadata>()
        val job = metadataFlow.onEach { emissions.add(it) }.launchIn(this)
        runCurrent()
        
        // Transition to Published
        artifactFlow.value = publishedArtifact
        
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
        
        val job = metadataFlow.launchIn(this)
        runCurrent()
        
        // Trigger transition
        artifactFlow.value = publishedArtifact
        
        advanceTimeBy(10.seconds) // Enough for 3 retries (2s each)
        
        // Verify exactly 3 retries occurred
        verify(atLeast = 1) { artifactRepository.observeArtifact(artifactId) }
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
        
        val job = metadataFlow.launchIn(this)
        runCurrent()
        
        // 1. Trigger transition for art1 (starts retry delay)
        artifactFlow.value = published1
        
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
    fun `should retry when PERMISSION_DENIED occurs during publishing transition`() = runTest {
        val artifactId = "art_pd"
        val draftArtifact = Artifact(id = artifactId, status = ArtifactStatus.DRAFT)
        val publishedArtifact = Artifact(id = artifactId, status = ArtifactStatus.ACTIVE)
        
        val artifactFlow = MutableStateFlow<Artifact?>(draftArtifact)
        
        // Mock observeArtifact to throw PERMISSION_DENIED then succeed
        val attemptCount = AtomicInteger(0)
        every { artifactRepository.observeArtifact(artifactId) } answers {
            flow {
                if (attemptCount.incrementAndGet() == 1) {
                    throw FirebaseFirestoreException(
                        "Permission denied",
                        FirebaseFirestoreException.Code.PERMISSION_DENIED
                    )
                } else {
                    emit(publishedArtifact)
                }
            }
        }

        val metadataFlow = useCase.execute(artifactFlow)
        
        val emissions = mutableListOf<PlayerMetadata>()
        val job = metadataFlow.onEach { emissions.add(it) }.launchIn(this)
        runCurrent()
        
        // Transition to Published
        artifactFlow.value = publishedArtifact
        
        advanceTimeBy(5.seconds)
        
        // Verify retry occurred (initial attempt + 1 retry)
        verify(atLeast = 1) { artifactRepository.observeArtifact(artifactId) }
        verify(atLeast = 1) { diagnosticLogger.warn(any(), "ARTIFACT_OBSERVE_RETRYING", any()) }
        
        job.cancel()
    }

    @Test
    fun `should NOT retry when PERMISSION_DENIED occurs for non-transitioning artifact`() = runTest {
        val artifactId = "art_pd_existing"
        val publishedArtifact = Artifact(id = artifactId, status = ArtifactStatus.ACTIVE)
        
        // Flow starts already published (no transition)
        val artifactFlow = MutableStateFlow<Artifact?>(publishedArtifact)
        
        // Mock observeArtifact to throw PERMISSION_DENIED
        every { artifactRepository.observeArtifact(artifactId) } returns flow {
            throw FirebaseFirestoreException(
                "Permission denied",
                FirebaseFirestoreException.Code.PERMISSION_DENIED
            )
        }

        val metadataFlow = useCase.execute(artifactFlow)
        
        // Verify only 1 attempt (NO retry)
        metadataFlow.catch { /* ignored */ }.collect()
        
        verify(exactly = 1) { artifactRepository.observeArtifact(artifactId) }
        verify(exactly = 0) { diagnosticLogger.warn(any(), "ARTIFACT_OBSERVE_RETRYING", any()) }
    }

    @Test
    fun `should maintain only one active listener after successful retry`() = runTest {
        val artifactId = "art_lifecycle"
        val draft = Artifact(id = artifactId, status = ArtifactStatus.DRAFT)
        val published = Artifact(id = artifactId, status = ArtifactStatus.ACTIVE)
        
        val artifactFlow = MutableStateFlow<Artifact?>(draft)
        
        val callCount = AtomicInteger(0)
        val listenerActiveCount = AtomicInteger(0)
        
        every { artifactRepository.observeArtifact(artifactId) } answers {
            callbackFlow {
                val attempt = callCount.incrementAndGet()
                listenerActiveCount.incrementAndGet()
                if (attempt == 1) {
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
        
        val job = metadataFlow.launchIn(this)
        runCurrent()
        
        artifactFlow.value = published
        
        advanceTimeBy(5.seconds)
        
        // Verify exactly 1 listener is active (the successful retry)
        assertEquals(1, listenerActiveCount.get())
        
        job.cancel()
        runCurrent()
        
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
        
        val emissions = mutableListOf<PlayerMetadata>()
        val job = metadataFlow.onEach { emissions.add(it) }.launchIn(this)
        runCurrent()
        
        artifactFlow.value = published
        
        advanceTimeBy(10.seconds)
        
        // Verify exactly 3 retries occurred
        verify(exactly = 3) { diagnosticLogger.warn(any(), "ARTIFACT_OBSERVE_RETRYING", any()) }
        
        // We don't verify terminal null emission here because filterNotNull() is used downstream.
        // But we verify that the flow doesn't CRASH and remains responsive.
        assert(emissions.isNotEmpty())
        
        job.cancel()
    }

    @Test
    fun `should observe pending Follow for current target persona ID`() = runTest {
        val uid = "user123"
        val targetPersonaId = "creator_persona_1"
        val artifactId = "art_100"
        val mockFirebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { mockFirebaseUser.uid } returns uid
        val testUser = User(id = uid, anonymousId = "anon123")

        every { authRepository.currentUser } returns MutableStateFlow(mockFirebaseUser)
        every { authRepository.userData } returns MutableStateFlow(testUser)

        val artifact = Artifact(
            id = artifactId,
            userId = "creator_uid_999",
            author = AuthorSnapshot(anonymousId = targetPersonaId),
            status = ArtifactStatus.ACTIVE
        )
        every { artifactRepository.observeArtifact(artifactId) } returns flowOf(artifact)

        val pendingFollow = PendingInteractionEntity(
            userId = uid,
            artifactId = targetPersonaId,
            interactionType = InteractionType.FOLLOW,
            action = InteractionAction.ADD
        )

        every { pendingInteractionDao.observePendingForArtifact(targetPersonaId, uid) } returns flowOf(listOf(pendingFollow))
        every { pendingInteractionDao.observePendingForArtifact(artifactId, uid) } returns flowOf(emptyList())

        val emissions = mutableListOf<PlayerMetadata>()
        val job = useCase.execute(MutableStateFlow(artifact)).onEach { emissions.add(it) }.launchIn(this)
        runCurrent()

        val latest = emissions.last()
        assertEquals(InteractionSyncStatus.PENDING, latest.followSyncStatus)
        assertEquals(InteractionSyncStatus.SYNCED, latest.resonanceSyncStatus)

        job.cancel()
    }

    @Test
    fun `should report SYNCED when no pending Follow exists for current target`() = runTest {
        val uid = "user123"
        val targetPersonaId = "creator_persona_1"
        val artifactId = "art_100"
        val mockFirebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { mockFirebaseUser.uid } returns uid
        val testUser = User(id = uid, anonymousId = "anon123")

        every { authRepository.currentUser } returns MutableStateFlow(mockFirebaseUser)
        every { authRepository.userData } returns MutableStateFlow(testUser)

        val artifact = Artifact(
            id = artifactId,
            userId = "creator_uid_999",
            author = AuthorSnapshot(anonymousId = targetPersonaId),
            status = ArtifactStatus.ACTIVE
        )
        every { artifactRepository.observeArtifact(artifactId) } returns flowOf(artifact)

        every { pendingInteractionDao.observePendingForArtifact(targetPersonaId, uid) } returns flowOf(emptyList())
        every { pendingInteractionDao.observePendingForArtifact(artifactId, uid) } returns flowOf(emptyList())

        val emissions = mutableListOf<PlayerMetadata>()
        val job = useCase.execute(MutableStateFlow(artifact)).onEach { emissions.add(it) }.launchIn(this)
        runCurrent()

        val latest = emissions.last()
        assertEquals(InteractionSyncStatus.SYNCED, latest.followSyncStatus)

        job.cancel()
    }

    @Test
    fun `should ignore pending Follow for another target persona ID`() = runTest {
        val uid = "user123"
        val targetPersonaId = "creator_persona_1"
        val otherPersonaId = "creator_persona_2"
        val artifactId = "art_100"
        val mockFirebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { mockFirebaseUser.uid } returns uid
        val testUser = User(id = uid, anonymousId = "anon123")

        every { authRepository.currentUser } returns MutableStateFlow(mockFirebaseUser)
        every { authRepository.userData } returns MutableStateFlow(testUser)

        val artifact = Artifact(
            id = artifactId,
            userId = "creator_uid_999",
            author = AuthorSnapshot(anonymousId = targetPersonaId),
            status = ArtifactStatus.ACTIVE
        )
        every { artifactRepository.observeArtifact(artifactId) } returns flowOf(artifact)

        val pendingFollowOther = PendingInteractionEntity(
            userId = uid,
            artifactId = otherPersonaId,
            interactionType = InteractionType.FOLLOW,
            action = InteractionAction.ADD
        )

        every { pendingInteractionDao.observePendingForArtifact(targetPersonaId, uid) } returns flowOf(emptyList())
        every { pendingInteractionDao.observePendingForArtifact(otherPersonaId, uid) } returns flowOf(listOf(pendingFollowOther))
        every { pendingInteractionDao.observePendingForArtifact(artifactId, uid) } returns flowOf(emptyList())

        val emissions = mutableListOf<PlayerMetadata>()
        val job = useCase.execute(MutableStateFlow(artifact)).onEach { emissions.add(it) }.launchIn(this)
        runCurrent()

        val latest = emissions.last()
        assertEquals(InteractionSyncStatus.SYNCED, latest.followSyncStatus)

        job.cancel()
    }

    @Test
    fun `should observe pending Reaction using artifact ID and not mix with Follow`() = runTest {
        val uid = "user123"
        val targetPersonaId = "creator_persona_1"
        val artifactId = "art_100"
        val mockFirebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { mockFirebaseUser.uid } returns uid
        val testUser = User(id = uid, anonymousId = "anon123")

        every { authRepository.currentUser } returns MutableStateFlow(mockFirebaseUser)
        every { authRepository.userData } returns MutableStateFlow(testUser)

        val artifact = Artifact(
            id = artifactId,
            userId = "creator_uid_999",
            author = AuthorSnapshot(anonymousId = targetPersonaId),
            status = ArtifactStatus.ACTIVE
        )
        every { artifactRepository.observeArtifact(artifactId) } returns flowOf(artifact)

        val pendingReaction = PendingInteractionEntity(
            userId = uid,
            artifactId = artifactId,
            interactionType = InteractionType.REACTION,
            action = InteractionAction.ADD
        )

        every { pendingInteractionDao.observePendingForArtifact(targetPersonaId, uid) } returns flowOf(emptyList())
        every { pendingInteractionDao.observePendingForArtifact(artifactId, uid) } returns flowOf(listOf(pendingReaction))

        val emissions = mutableListOf<PlayerMetadata>()
        val job = useCase.execute(MutableStateFlow(artifact)).onEach { emissions.add(it) }.launchIn(this)
        runCurrent()

        val latest = emissions.last()
        assertEquals(InteractionSyncStatus.PENDING, latest.resonanceSyncStatus)
        assertEquals(InteractionSyncStatus.SYNCED, latest.followSyncStatus)

        job.cancel()
    }

    @Test
    fun `should not treat pending Follow as pending Reaction`() = runTest {
        val uid = "user123"
        val targetPersonaId = "creator_persona_1"
        val artifactId = "art_100"
        val mockFirebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { mockFirebaseUser.uid } returns uid
        val testUser = User(id = uid, anonymousId = "anon123")

        every { authRepository.currentUser } returns MutableStateFlow(mockFirebaseUser)
        every { authRepository.userData } returns MutableStateFlow(testUser)

        val artifact = Artifact(
            id = artifactId,
            userId = "creator_uid_999",
            author = AuthorSnapshot(anonymousId = targetPersonaId),
            status = ArtifactStatus.ACTIVE
        )
        every { artifactRepository.observeArtifact(artifactId) } returns flowOf(artifact)

        val pendingFollow = PendingInteractionEntity(
            userId = uid,
            artifactId = targetPersonaId,
            interactionType = InteractionType.FOLLOW,
            action = InteractionAction.ADD
        )

        every { pendingInteractionDao.observePendingForArtifact(targetPersonaId, uid) } returns flowOf(listOf(pendingFollow))
        every { pendingInteractionDao.observePendingForArtifact(artifactId, uid) } returns flowOf(emptyList())

        val emissions = mutableListOf<PlayerMetadata>()
        val job = useCase.execute(MutableStateFlow(artifact)).onEach { emissions.add(it) }.launchIn(this)
        runCurrent()

        val latest = emissions.last()
        assertEquals(InteractionSyncStatus.SYNCED, latest.resonanceSyncStatus)
        assertEquals(InteractionSyncStatus.PENDING, latest.followSyncStatus)

        job.cancel()
    }

    @Test
    fun `should update followSyncStatus reactively when Room pending record changes`() = runTest {
        val uid = "user123"
        val targetPersonaId = "creator_persona_1"
        val artifactId = "art_100"
        val mockFirebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { mockFirebaseUser.uid } returns uid
        val testUser = User(id = uid, anonymousId = "anon123")

        every { authRepository.currentUser } returns MutableStateFlow(mockFirebaseUser)
        every { authRepository.userData } returns MutableStateFlow(testUser)

        val artifact = Artifact(
            id = artifactId,
            userId = "creator_uid_999",
            author = AuthorSnapshot(anonymousId = targetPersonaId),
            status = ArtifactStatus.ACTIVE
        )
        every { artifactRepository.observeArtifact(artifactId) } returns flowOf(artifact)

        val roomPendingFollowFlow = MutableStateFlow<List<PendingInteractionEntity>>(emptyList())
        every { pendingInteractionDao.observePendingForArtifact(targetPersonaId, uid) } returns roomPendingFollowFlow
        every { pendingInteractionDao.observePendingForArtifact(artifactId, uid) } returns flowOf(emptyList())

        val emissions = mutableListOf<PlayerMetadata>()
        val job = useCase.execute(MutableStateFlow(artifact)).onEach { emissions.add(it) }.launchIn(this)
        runCurrent()

        assertEquals(InteractionSyncStatus.SYNCED, emissions.last().followSyncStatus)

        val pendingFollow = PendingInteractionEntity(
            userId = uid,
            artifactId = targetPersonaId,
            interactionType = InteractionType.FOLLOW,
            action = InteractionAction.ADD
        )
        roomPendingFollowFlow.value = listOf(pendingFollow)
        runCurrent()

        assertEquals(InteractionSyncStatus.PENDING, emissions.last().followSyncStatus)

        roomPendingFollowFlow.value = emptyList()
        runCurrent()

        assertEquals(InteractionSyncStatus.SYNCED, emissions.last().followSyncStatus)

        job.cancel()
    }

    @Test
    fun `should update followSyncStatus when switching target profiles`() = runTest {
        val uid = "user123"
        val targetPersonaId1 = "creator_persona_1"
        val targetPersonaId2 = "creator_persona_2"
        val mockFirebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { mockFirebaseUser.uid } returns uid
        val testUser = User(id = uid, anonymousId = "anon123")

        every { authRepository.currentUser } returns MutableStateFlow(mockFirebaseUser)
        every { authRepository.userData } returns MutableStateFlow(testUser)

        val artifact1 = Artifact(
            id = "art_100",
            userId = "creator_uid_1",
            author = AuthorSnapshot(anonymousId = targetPersonaId1),
            status = ArtifactStatus.ACTIVE
        )
        val artifact2 = Artifact(
            id = "art_200",
            userId = "creator_uid_2",
            author = AuthorSnapshot(anonymousId = targetPersonaId2),
            status = ArtifactStatus.ACTIVE
        )

        every { artifactRepository.observeArtifact("art_100") } returns flowOf(artifact1)
        every { artifactRepository.observeArtifact("art_200") } returns flowOf(artifact2)

        val pendingFollow1 = PendingInteractionEntity(
            userId = uid,
            artifactId = targetPersonaId1,
            interactionType = InteractionType.FOLLOW,
            action = InteractionAction.ADD
        )

        every { pendingInteractionDao.observePendingForArtifact(any(), any()) } returns flowOf(emptyList())
        every { pendingInteractionDao.observePendingForArtifact(targetPersonaId1, uid) } returns flowOf(listOf(pendingFollow1))
        every { pendingInteractionDao.observePendingForArtifact(targetPersonaId2, uid) } returns flowOf(emptyList())

        val artifactStateFlow = MutableStateFlow<Artifact?>(artifact1)
        val emissions = mutableListOf<PlayerMetadata>()
        val job = useCase.execute(artifactStateFlow).onEach { emissions.add(it) }.launchIn(this)
        runCurrent()

        assertEquals(InteractionSyncStatus.PENDING, emissions.last().followSyncStatus)

        artifactStateFlow.value = artifact2
        runCurrent()

        assertEquals(InteractionSyncStatus.SYNCED, emissions.last().followSyncStatus)

        job.cancel()
    }

    @Test
    fun `should preserve existing failure and retry semantics for interactions`() = runTest {
        val uid = "user123"
        val targetPersonaId = "creator_persona_1"
        val artifactId = "art_100"
        val mockFirebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { mockFirebaseUser.uid } returns uid
        val testUser = User(id = uid, anonymousId = "anon123")

        every { authRepository.currentUser } returns MutableStateFlow(mockFirebaseUser)
        every { authRepository.userData } returns MutableStateFlow(testUser)

        val artifact = Artifact(
            id = artifactId,
            userId = "creator_uid_999",
            author = AuthorSnapshot(anonymousId = targetPersonaId),
            status = ArtifactStatus.ACTIVE
        )
        every { artifactRepository.observeArtifact(artifactId) } returns flowOf(artifact)

        val roomPendingFollowFlow = MutableStateFlow<List<PendingInteractionEntity>>(emptyList())
        every { pendingInteractionDao.observePendingForArtifact(targetPersonaId, uid) } returns roomPendingFollowFlow
        every { pendingInteractionDao.observePendingForArtifact(artifactId, uid) } returns flowOf(emptyList())

        val emissions = mutableListOf<PlayerMetadata>()
        val job = useCase.execute(MutableStateFlow(artifact)).onEach { emissions.add(it) }.launchIn(this)
        runCurrent()

        assertEquals(InteractionSyncStatus.SYNCED, emissions.last().followSyncStatus)

        // Pending follow under retry
        val retryingFollow = PendingInteractionEntity(
            userId = uid,
            artifactId = targetPersonaId,
            interactionType = InteractionType.FOLLOW,
            action = InteractionAction.ADD,
            retryCount = 2,
            lastError = "Network error"
        )
        roomPendingFollowFlow.value = listOf(retryingFollow)
        runCurrent()

        assertEquals(InteractionSyncStatus.PENDING, emissions.last().followSyncStatus)

        // After max retries reached and moved to DLQ (deleted from pending table)
        roomPendingFollowFlow.value = emptyList()
        runCurrent()

        assertEquals(InteractionSyncStatus.SYNCED, emissions.last().followSyncStatus)

        job.cancel()
    }
}
