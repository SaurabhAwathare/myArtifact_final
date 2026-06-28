package com.saurabh.artifact.worker

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.gson.Gson
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.ReactionRepository
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.repository.EngagementRepository
import com.saurabh.artifact.repository.CommentRepository
import com.saurabh.artifact.util.ArtifactLogger
import com.saurabh.artifact.util.NetworkUtils
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.After
import org.junit.Test
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker
import android.util.Log
import kotlin.Result as KResult

@OptIn(ExperimentalCoroutinesApi::class)
class InteractionSyncWorkerRecoveryTest {
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
        every { ArtifactLogger.w(any<String>(), any<String>(), any()) } just Runs
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
    fun `isTransientError should correctly classify AppErrors`() {
        assert(NetworkUtils.isTransientError(AppError.NetworkFailure()))
        assert(!NetworkUtils.isTransientError(AppError.PermissionDenied()))
        assert(!NetworkUtils.isTransientError(AppError.Unauthenticated()))
        assert(!NetworkUtils.isTransientError(AppError.NotFound("Type", "ID")))
        
        // Unknown with Retry path should be transient
        assert(NetworkUtils.isTransientError(AppError.Unknown(Exception(), recoveryPath = AppError.RecoveryPath.Retry)))
        
        // Unknown without Retry path should be permanent
        assert(!NetworkUtils.isTransientError(AppError.Unknown(Exception(), recoveryPath = null)))
    }

    @Test
    fun `worker should move to DLQ after max retries for transient error`() = runTest {
        val userId = "user123"
        val interaction = PendingInteractionEntity(
            id = 1,
            userId = userId,
            artifactId = "art123",
            interactionType = InteractionType.REACTION,
            action = InteractionAction.ADD,
            retryCount = InteractionSyncWorker.MAX_RETRIES // Already at limit
        )

        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns listOf(interaction)
        coEvery { reactionRepository.syncReactionToFirestore(any(), any(), any()) } returns 
            KResult.failure(AppError.NetworkFailure())

        val result = worker.doWork()

        assert(result is ListenableWorker.Result.Success) // Result.success because queue is drained
        coVerify(exactly = 1) { deadLetterInteractionDao.insert(any()) }
        coVerify(exactly = 1) { pendingInteractionDao.delete(interaction) }
    }

    @Test
    fun `worker should move to DLQ immediately for permanent error`() = runTest {
        val userId = "user123"
        val interaction = PendingInteractionEntity(
            id = 1,
            userId = userId,
            artifactId = "art123",
            interactionType = InteractionType.REACTION,
            action = InteractionAction.ADD,
            retryCount = 0
        )

        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns listOf(interaction)
        coEvery { reactionRepository.syncReactionToFirestore(any(), any(), any()) } returns 
            KResult.failure(AppError.PermissionDenied())

        val result = worker.doWork()

        assert(result is ListenableWorker.Result.Success)
        coVerify(exactly = 1) { deadLetterInteractionDao.insert(match { it.failureType == "PERMANENT" }) }
        coVerify(exactly = 1) { pendingInteractionDao.delete(interaction) }
    }

    @Test
    fun `worker should only process interactions for current user`() = runTest {
        coEvery { pendingInteractionDao.getPendingForUser(any()) } returns emptyList()

        worker.doWork()

        coVerify(atLeast = 1) { pendingInteractionDao.getPendingForUser(any()) }
    }

    @Test
    fun `worker should retry on transient failure if below limit`() = runTest {
        val userId = "user123"
        val interaction = PendingInteractionEntity(
            id = 1,
            userId = userId,
            artifactId = "art123",
            interactionType = InteractionType.REACTION,
            action = InteractionAction.ADD,
            retryCount = 0
        )

        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns listOf(interaction)
        coEvery { reactionRepository.syncReactionToFirestore(any(), any(), any()) } returns 
            KResult.failure(AppError.NetworkFailure())

        val result = worker.doWork()

        assert(result is ListenableWorker.Result.Retry)
        coVerify(exactly = 1) { pendingInteractionDao.insert(match { it.retryCount == 1 }) }
        coVerify(exactly = 0) { deadLetterInteractionDao.insert(any()) }
    }

    @Test
    fun `worker should break on transient failure to preserve ordering`() = runTest {
        val userId = "user123"
        val interaction1 = PendingInteractionEntity(id = 1, userId = userId, artifactId = "art1", interactionType = InteractionType.REACTION, action = InteractionAction.ADD)
        val interaction2 = PendingInteractionEntity(id = 2, userId = userId, artifactId = "art2", interactionType = InteractionType.REACTION, action = InteractionAction.ADD)

        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns listOf(interaction1, interaction2)
        coEvery { reactionRepository.syncReactionToFirestore("art1", any(), any()) } returns KResult.failure(AppError.NetworkFailure())

        val result = worker.doWork()

        assert(result is ListenableWorker.Result.Retry)
        coVerify(exactly = 1) { reactionRepository.syncReactionToFirestore("art1", any(), any()) }
        coVerify(exactly = 0) { reactionRepository.syncReactionToFirestore("art2", any(), any()) }
    }
}
