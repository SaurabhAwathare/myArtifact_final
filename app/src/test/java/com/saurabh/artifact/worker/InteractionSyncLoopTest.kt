package com.saurabh.artifact.worker

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.gson.Gson
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.domain.review.EngagementSyncPayload
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.ReactionRepository
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.repository.EngagementRepository
import com.saurabh.artifact.repository.CommentRepository
import com.saurabh.artifact.util.ArtifactLogger
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.After
import org.junit.Test
import androidx.work.WorkerParameters
import android.util.Log

@OptIn(ExperimentalCoroutinesApi::class)
class InteractionSyncLoopTest {
    private val pendingInteractionDao = mockk<PendingInteractionDao>(relaxed = true)
    private val deadLetterInteractionDao = mockk<DeadLetterInteractionDao>(relaxed = true)
    private val reactionRepository = mockk<ReactionRepository>(relaxed = true)
    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val engagementRepository = mockk<EngagementRepository>(relaxed = true)
    private val commentRepository = mockk<CommentRepository>(relaxed = true)
    private val auth = mockk<FirebaseAuth>()
    private val firebaseUser = mockk<FirebaseUser>()
    private val workerParams = mockk<WorkerParameters>(relaxed = true)
    private val gson = Gson()

    private lateinit var worker: InteractionSyncWorker

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        mockkStatic(FirebaseAuth::class)
        every { FirebaseAuth.getInstance() } returns auth
        every { auth.currentUser } returns firebaseUser
        every { firebaseUser.uid } returns "user123"

        mockkObject(ArtifactLogger)
        every { ArtifactLogger.d(any<String>(), any<String>()) } just Runs
        every { ArtifactLogger.i(any<String>(), any<String>()) } just Runs
        every { ArtifactLogger.logInteraction(any(), any(), any()) } just Runs

        worker = InteractionSyncWorker(
            appContext = mockk(relaxed = true),
            workerParams = workerParams,
            pendingInteractionDao = pendingInteractionDao,
            deadLetterInteractionDao = deadLetterInteractionDao,
            reactionRepository = reactionRepository,
            artifactRepository = artifactRepository,
            userRepository = userRepository,
            engagementRepository = engagementRepository,
            commentRepository = commentRepository,
            gson = gson
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `worker should call internal sync API and NOT re-enqueue for Reaction ADD`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val interaction = PendingInteractionEntity(
            id = 1,
            userId = userId,
            artifactId = artifactId,
            interactionType = InteractionType.REACTION,
            action = InteractionAction.ADD,
            metadata = ReactionType.I_HEAR_YOU.id
        )

        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns listOf(interaction)
        coEvery { reactionRepository.syncReactionToFirestore(any(), any(), any()) } returns Result.success(Unit)

        worker.doWork()

        coVerify(exactly = 1) { reactionRepository.syncReactionToFirestore(artifactId, userId, ReactionType.I_HEAR_YOU) }
        coVerify(exactly = 0) { reactionRepository.reactToArtifact(any(), any(), any()) }
        coVerify(exactly = 1) { pendingInteractionDao.delete(interaction) }
    }

    @Test
    fun `worker should call internal sync API and NOT re-enqueue for Reaction REMOVE`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val interaction = PendingInteractionEntity(
            id = 1,
            userId = userId,
            artifactId = artifactId,
            interactionType = InteractionType.REACTION,
            action = InteractionAction.REMOVE
        )

        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns listOf(interaction)
        coEvery { reactionRepository.syncReactionRemovalFromFirestore(any(), any()) } returns Result.success(Unit)

        worker.doWork()

        coVerify(exactly = 1) { reactionRepository.syncReactionRemovalFromFirestore(artifactId, userId) }
        coVerify(exactly = 0) { reactionRepository.removeReaction(any(), any()) }
        coVerify(exactly = 1) { pendingInteractionDao.delete(interaction) }
    }

    @Test
    fun `worker should call internal sync API and NOT re-enqueue for Follow ADD`() = runTest {
        val targetUserId = "target456"
        val userId = "user123"
        val interaction = PendingInteractionEntity(
            id = 2,
            userId = userId,
            artifactId = targetUserId,
            interactionType = InteractionType.FOLLOW,
            action = InteractionAction.ADD
        )

        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns listOf(interaction)
        coEvery { userRepository.syncFollowToFirestore(any(), any()) } returns Result.success(Unit)

        worker.doWork()

        coVerify(exactly = 1) { userRepository.syncFollowToFirestore(userId, targetUserId) }
        coVerify(exactly = 0) { userRepository.resonateWithUser(any(), any()) }
        coVerify(exactly = 1) { pendingInteractionDao.delete(interaction) }
    }

    @Test
    fun `worker should call internal sync API and NOT re-enqueue for Follow REMOVE`() = runTest {
        val targetUserId = "target456"
        val userId = "user123"
        val interaction = PendingInteractionEntity(
            id = 2,
            userId = userId,
            artifactId = targetUserId,
            interactionType = InteractionType.FOLLOW,
            action = InteractionAction.REMOVE
        )

        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns listOf(interaction)
        coEvery { userRepository.syncUnfollowFromFirestore(any(), any()) } returns Result.success(Unit)

        worker.doWork()

        coVerify(exactly = 1) { userRepository.syncUnfollowFromFirestore(userId, targetUserId) }
        coVerify(exactly = 0) { userRepository.stopResonatingWithUser(any(), any()) }
        coVerify(exactly = 1) { pendingInteractionDao.delete(interaction) }
    }

    @Test
    fun `worker should call internal sync API and NOT re-enqueue for Save ADD`() = runTest {
        val artifactId = "art789"
        val userId = "user123"
        val interaction = PendingInteractionEntity(
            id = 3,
            userId = userId,
            artifactId = artifactId,
            interactionType = InteractionType.SAVE,
            action = InteractionAction.ADD,
            metadata = "My Shelf"
        )

        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns listOf(interaction)
        coEvery { artifactRepository.saveArtifactToFirestore(any(), any(), any()) } returns Result.success(Unit)

        worker.doWork()

        coVerify(exactly = 1) { artifactRepository.saveArtifactToFirestore(userId, artifactId, "My Shelf") }
        coVerify(exactly = 0) { artifactRepository.saveArtifact(any(), any(), any()) }
        coVerify(exactly = 1) { pendingInteractionDao.delete(interaction) }
    }

    @Test
    fun `worker should call internal sync API and NOT re-enqueue for Save REMOVE`() = runTest {
        val artifactId = "art789"
        val userId = "user123"
        val interaction = PendingInteractionEntity(
            id = 3,
            userId = userId,
            artifactId = artifactId,
            interactionType = InteractionType.SAVE,
            action = InteractionAction.REMOVE
        )

        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns listOf(interaction)
        coEvery { artifactRepository.unsaveArtifactFromFirestore(any(), any()) } returns Result.success(Unit)

        worker.doWork()

        coVerify(exactly = 1) { artifactRepository.unsaveArtifactFromFirestore(userId, artifactId) }
        coVerify(exactly = 0) { artifactRepository.unsaveArtifact(any(), any()) }
        coVerify(exactly = 1) { pendingInteractionDao.delete(interaction) }
    }

    @Test
    fun `worker should call internal sync API and NOT re-enqueue for Engagement`() = runTest {
        val artifactId = "art_engagement"
        val userId = "user123"
        val payload = EngagementSyncPayload(
            artifactId = artifactId,
            lastPositionMs = 1000L,
            furthestPositionMs = 2000L,
            durationMs = 5000L,
            hasReachedEnd = false,
            coverage = "AQID",
            lastUpdated = 999L
        )
        val interaction = PendingInteractionEntity(
            id = 4,
            userId = userId,
            artifactId = artifactId,
            interactionType = InteractionType.ENGAGEMENT,
            action = InteractionAction.ADD,
            metadata = gson.toJson(payload)
        )

        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns listOf(interaction)
        coEvery { engagementRepository.syncEngagementToFirestore(any(), any()) } returns Result.success(Unit)

        worker.doWork()

        coVerify(exactly = 1) { engagementRepository.syncEngagementToFirestore(userId, any()) }
        coVerify(exactly = 1) { pendingInteractionDao.delete(interaction) }
    }
}
