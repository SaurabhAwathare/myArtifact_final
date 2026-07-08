package com.saurabh.artifact.worker

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.gson.Gson
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.*
import com.saurabh.artifact.domain.review.comments.CommentUnlockPolicy
import com.saurabh.artifact.domain.review.comments.CommentUnlockValidator
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.util.ArtifactLogger
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import androidx.work.WorkerParameters
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class InteractionSyncWorkerCommentTest {
    private val pendingInteractionDao = mockk<PendingInteractionDao>(relaxed = true)
    private val deadLetterInteractionDao = mockk<DeadLetterInteractionDao>(relaxed = true)
    private val reactionRepository = mockk<ReactionRepository>(relaxed = true)
    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val engagementRepository = mockk<EngagementRepository>(relaxed = true)
    private val commentRepository = mockk<CommentRepository>(relaxed = true)
    private val commentUnlockValidator = mockk<CommentUnlockValidator>(relaxed = true)
    private val commentUnlockPolicy = CommentUnlockPolicy()
    private val gson = Gson()
    private val workerParams = mockk<WorkerParameters>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var worker: InteractionSyncWorker

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        
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
            commentUnlockValidator = commentUnlockValidator,
            commentUnlockPolicy = commentUnlockPolicy,
            gson = gson,
            diagnosticLogger = diagnosticLogger
        )

        mockkStatic(FirebaseAuth::class)
        val auth = mockk<FirebaseAuth>()
        val firebaseUser = mockk<FirebaseUser>()
        every { FirebaseAuth.getInstance() } returns auth
        every { auth.currentUser } returns firebaseUser
        every { firebaseUser.uid } returns "user123"
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `worker should call commentRepository syncCommentToFirestore`() = runTest(timeout = 10.seconds) {
        val payload = CommentSyncPayload(
            commentId = "c123",
            artifactId = "a123",
            content = "test",
            visibility = VisibilityLayer.SANCTUARY,
            authorType = AuthorType.PSEUDONYM,
            authorName = "User",
            authorAvatarSeed = "seed",
            artifactOwnerId = "owner123",
            moderationState = CommentModerationState.APPROVED,
            createdAtMillis = 123456789L
        )
        val interaction = PendingInteractionEntity(
            userId = "user123",
            artifactId = "a123",
            interactionType = InteractionType.COMMENT,
            action = InteractionAction.ADD,
            metadata = gson.toJson(payload)
        )

        coEvery { pendingInteractionDao.getPendingForUser("user123") } returns listOf(interaction)
        coEvery { commentRepository.syncCommentToFirestore(any(), any()) } returns Result.success(Unit)

        worker.doWork()

        coVerify { commentRepository.syncCommentToFirestore("user123", any()) }
        coVerify { pendingInteractionDao.delete(interaction) }
    }

    @Test
    fun `worker should call commentRepository syncCommentReactionToFirestore`() = runTest(timeout = 10.seconds) {
        val interaction = PendingInteractionEntity(
            userId = "user123",
            artifactId = "comment123",
            interactionType = InteractionType.COMMENT_REACTION,
            action = InteractionAction.ADD,
            metadata = ReactionType.I_HEAR_YOU.id
        )

        coEvery { pendingInteractionDao.getPendingForUser("user123") } returns listOf(interaction)
        coEvery { commentRepository.syncCommentReactionToFirestore(any(), any()) } returns Result.success(Unit)

        worker.doWork()

        coVerify { commentRepository.syncCommentReactionToFirestore("comment123", any()) }
        coVerify { pendingInteractionDao.delete(interaction) }
    }

    @Test
    fun `worker should retry comment if PERMISSION_DENIED and local evidence exists`() = runTest(timeout = 10.seconds) {
        val artifactId = "a123"
        val payload = CommentSyncPayload(
            commentId = "c123",
            artifactId = artifactId,
            content = "test",
            visibility = VisibilityLayer.SANCTUARY,
            authorType = AuthorType.PSEUDONYM,
            authorName = "User",
            authorAvatarSeed = "seed",
            artifactOwnerId = "owner123",
            moderationState = CommentModerationState.APPROVED,
            createdAtMillis = 123456789L
        )
        val interaction = PendingInteractionEntity(
            userId = "user123",
            artifactId = artifactId,
            interactionType = InteractionType.COMMENT,
            action = InteractionAction.ADD,
            metadata = gson.toJson(payload)
        )

        coEvery { pendingInteractionDao.getPendingForUser("user123") } returns listOf(interaction)
        
        val firestoreError = mockk<com.google.firebase.firestore.FirebaseFirestoreException>(relaxed = true)
        val mockCode = mockk<com.google.firebase.firestore.FirebaseFirestoreException.Code>(relaxed = true)
        every { mockCode.name } returns "PERMISSION_DENIED"
        every { firestoreError.code } returns mockCode
        coEvery { commentRepository.syncCommentToFirestore(any(), any()) } returns Result.failure(firestoreError)

        // Mock local evidence as valid
        val mockEvidence = mockk<com.saurabh.artifact.domain.review.EngagementEvidence>()
        coEvery { engagementRepository.getEngagement(artifactId) } returns Result.success(mockEvidence)
        every { commentUnlockValidator.validate(mockEvidence, any()) } returns com.saurabh.artifact.audio.validation.ReviewResult(
            coveragePercent = 1.0f,
            reachedEnd = true,
            isValid = true
        )

        val result = worker.doWork()

        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
        coVerify(exactly = 0) { pendingInteractionDao.delete(interaction) }
        coVerify(exactly = 0) { deadLetterInteractionDao.insert(any()) }
    }

    @Test
    fun `worker should NOT retry comment if PERMISSION_DENIED and NO local evidence exists`() = runTest(timeout = 10.seconds) {
        val artifactId = "a123"
        val payload = CommentSyncPayload(
            commentId = "c123",
            artifactId = artifactId,
            content = "test",
            visibility = VisibilityLayer.SANCTUARY,
            authorType = AuthorType.PSEUDONYM,
            authorName = "User",
            authorAvatarSeed = "seed",
            artifactOwnerId = "owner123",
            moderationState = CommentModerationState.APPROVED,
            createdAtMillis = 123456789L
        )
        val interaction = PendingInteractionEntity(
            userId = "user123",
            artifactId = artifactId,
            interactionType = InteractionType.COMMENT,
            action = InteractionAction.ADD,
            metadata = gson.toJson(payload)
        )

        coEvery { pendingInteractionDao.getPendingForUser("user123") } returns listOf(interaction)
        
        val firestoreError = mockk<com.google.firebase.firestore.FirebaseFirestoreException>(relaxed = true)
        val mockCode = mockk<com.google.firebase.firestore.FirebaseFirestoreException.Code>(relaxed = true)
        every { mockCode.name } returns "PERMISSION_DENIED"
        every { firestoreError.code } returns mockCode
        coEvery { commentRepository.syncCommentToFirestore(any(), any()) } returns Result.failure(firestoreError)

        // Mock local evidence as INVALID
        coEvery { engagementRepository.getEngagement(artifactId) } returns Result.failure(Exception("Not found"))

        val result = worker.doWork()

        // Should NOT retry, should move to DLQ
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify { pendingInteractionDao.delete(interaction) }
        coVerify { deadLetterInteractionDao.insert(any()) }
    }
}
