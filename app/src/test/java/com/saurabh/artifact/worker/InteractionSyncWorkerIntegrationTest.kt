package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.gson.Gson
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.domain.review.EngagementSyncPayload
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.*
import com.saurabh.artifact.util.ArtifactLogger
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.Result as KResult

@OptIn(ExperimentalCoroutinesApi::class)
class InteractionSyncWorkerIntegrationTest {
    private val pendingInteractionDao = mockk<PendingInteractionDao>(relaxed = true)
    private val deadLetterInteractionDao = mockk<DeadLetterInteractionDao>(relaxed = true)
    private val reactionRepository = mockk<ReactionRepository>(relaxed = true)
    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val engagementRepository = mockk<EngagementRepository>(relaxed = true)
    private val commentRepository = mockk<CommentRepository>(relaxed = true)
    private val workerParams = mockk<WorkerParameters>(relaxed = true)
    private val gson = Gson()

    private lateinit var worker: InteractionSyncWorker
    private val userId = "user123"

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0

        mockkStatic(FirebaseAuth::class)
        val auth = mockk<FirebaseAuth>()
        val firebaseUser = mockk<FirebaseUser>()
        every { FirebaseAuth.getInstance() } returns auth
        every { auth.currentUser } returns firebaseUser
        every { firebaseUser.uid } returns userId

        mockkObject(ArtifactLogger)
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
    fun `worker should process REACTION ADD`() = runTest {
        val interaction = PendingInteractionEntity(
            userId = userId,
            artifactId = "art1",
            interactionType = InteractionType.REACTION,
            action = InteractionAction.ADD,
            metadata = ReactionType.I_HEAR_YOU.id
        )
        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns listOf(interaction)
        coEvery { reactionRepository.syncReactionToFirestore(any(), any(), any()) } returns KResult.success(Unit)

        worker.doWork()

        coVerify { reactionRepository.syncReactionToFirestore("art1", userId, ReactionType.I_HEAR_YOU) }
        coVerify { pendingInteractionDao.delete(interaction) }
    }

    @Test
    fun `worker should process SAVE ADD`() = runTest {
        val interaction = PendingInteractionEntity(
            userId = userId,
            artifactId = "art1",
            interactionType = InteractionType.SAVE,
            action = InteractionAction.ADD,
            metadata = "Favorites"
        )
        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns listOf(interaction)
        coEvery { artifactRepository.saveArtifactToFirestore(any(), any(), any()) } returns KResult.success(Unit)

        worker.doWork()

        coVerify { artifactRepository.saveArtifactToFirestore(userId, "art1", "Favorites") }
        coVerify { pendingInteractionDao.delete(interaction) }
    }

    @Test
    fun `worker should process FOLLOW ADD`() = runTest {
        val targetId = "targetUser"
        val interaction = PendingInteractionEntity(
            userId = userId,
            artifactId = targetId,
            interactionType = InteractionType.FOLLOW,
            action = InteractionAction.ADD
        )
        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns listOf(interaction)
        coEvery { userRepository.syncFollowToFirestore(any(), any()) } returns KResult.success(Unit)

        worker.doWork()

        coVerify { userRepository.syncFollowToFirestore(userId, targetId) }
        coVerify { pendingInteractionDao.delete(interaction) }
    }

    @Test
    fun `worker should process ENGAGEMENT`() = runTest {
        val payload = EngagementSyncPayload("art1", 1000, 1000, 5000, false, "AQID", 123L)
        val interaction = PendingInteractionEntity(
            userId = userId,
            artifactId = "art1",
            interactionType = InteractionType.ENGAGEMENT,
            action = InteractionAction.ADD,
            metadata = gson.toJson(payload)
        )
        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns listOf(interaction)
        coEvery { engagementRepository.syncEngagementToFirestore(any(), any()) } returns KResult.success(Unit)

        worker.doWork()

        coVerify { engagementRepository.syncEngagementToFirestore(userId, match { it.artifactId == "art1" }) }
        coVerify { pendingInteractionDao.delete(interaction) }
    }

    @Test
    fun `worker should process COMMENT_REACTION`() = runTest {
        val interaction = PendingInteractionEntity(
            userId = userId,
            artifactId = "comment1",
            interactionType = InteractionType.COMMENT_REACTION,
            action = InteractionAction.ADD,
            metadata = ReactionType.I_HEAR_YOU.id
        )
        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns listOf(interaction)
        coEvery { commentRepository.syncCommentReactionToFirestore(any(), any()) } returns KResult.success(Unit)

        worker.doWork()

        coVerify { commentRepository.syncCommentReactionToFirestore("comment1", ReactionType.I_HEAR_YOU) }
        coVerify { pendingInteractionDao.delete(interaction) }
    }
}
