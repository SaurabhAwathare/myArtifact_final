package com.saurabh.artifact.worker

import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.ReactionRepository
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.repository.EngagementRepository
import com.saurabh.artifact.repository.CommentRepository
import com.saurabh.artifact.domain.review.comments.CommentUnlockPolicy
import com.saurabh.artifact.domain.review.comments.CommentUnlockValidator
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.util.ArtifactLogger
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import androidx.work.WorkerParameters

@OptIn(ExperimentalCoroutinesApi::class)
class InteractionSyncWorkerCollapsingTest {
    private val pendingInteractionDao = mockk<PendingInteractionDao>(relaxed = true)
    private val deadLetterInteractionDao = mockk<DeadLetterInteractionDao>(relaxed = true)
    private val reactionRepository = mockk<ReactionRepository>(relaxed = true)
    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val engagementRepository = mockk<EngagementRepository>(relaxed = true)
    private val commentRepository = mockk<CommentRepository>(relaxed = true)
    private val commentUnlockValidator = mockk<CommentUnlockValidator>(relaxed = true)
    private val commentUnlockPolicy = CommentUnlockPolicy()
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
        every { ArtifactLogger.d(any<String>(), any<String>()) } just Runs
        every { ArtifactLogger.i(any<String>(), any<String>()) } just Runs
        every { ArtifactLogger.w(any<String>(), any<String>()) } just Runs

        worker = spyk(InteractionSyncWorker(
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
            gson = mockk(relaxed = true),
            diagnosticLogger = diagnosticLogger
        ))
    }

    @Test
    fun `collapseEvents should keep only the latest toggle for SAVE`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val events = listOf(
            PendingInteractionEntity(id = 1, userId = userId, artifactId = artifactId, interactionType = InteractionType.SAVE, action = InteractionAction.ADD),
            PendingInteractionEntity(id = 2, userId = userId, artifactId = artifactId, interactionType = InteractionType.SAVE, action = InteractionAction.REMOVE),
            PendingInteractionEntity(id = 3, userId = userId, artifactId = artifactId, interactionType = InteractionType.SAVE, action = InteractionAction.ADD)
        )
        
        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns events

        worker.collapseEvents(userId)

        coVerify(exactly = 1) { pendingInteractionDao.delete(events[0]) }
        coVerify(exactly = 1) { pendingInteractionDao.delete(events[1]) }
        coVerify(exactly = 0) { pendingInteractionDao.delete(events[2]) }
    }

    @Test
    fun `collapseEvents should cancel out redundant ADD-REMOVE cycles`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val events = listOf(
            PendingInteractionEntity(id = 1, userId = userId, artifactId = artifactId, interactionType = InteractionType.SAVE, action = InteractionAction.ADD),
            PendingInteractionEntity(id = 2, userId = userId, artifactId = artifactId, interactionType = InteractionType.SAVE, action = InteractionAction.REMOVE)
        )
        
        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns events

        worker.collapseEvents(userId)

        coVerify(exactly = 1) { pendingInteractionDao.delete(events[0]) }
        coVerify(exactly = 1) { pendingInteractionDao.delete(events[1]) }
    }

    @Test
    fun `collapseEvents should NOT delete REMOVE-ADD sequence (Intimacy Preservation)`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val events = listOf(
            PendingInteractionEntity(id = 1, userId = userId, artifactId = artifactId, interactionType = InteractionType.REACTION, action = InteractionAction.REMOVE),
            PendingInteractionEntity(id = 2, userId = userId, artifactId = artifactId, interactionType = InteractionType.REACTION, action = InteractionAction.ADD, metadata = "STAYED_WITH_ME")
        )
        
        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns events

        worker.collapseEvents(userId)

        // Only the REMOVE should be deleted, the ADD should survive as the latest intent.
        coVerify(exactly = 1) { pendingInteractionDao.delete(events[0]) }
        coVerify(exactly = 0) { pendingInteractionDao.delete(events[1]) }
    }

    @Test
    fun `collapseEvents should correctly handle ADD-REMOVE-ADD sequence`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val events = listOf(
            PendingInteractionEntity(id = 1, userId = userId, artifactId = artifactId, interactionType = InteractionType.SAVE, action = InteractionAction.ADD),
            PendingInteractionEntity(id = 2, userId = userId, artifactId = artifactId, interactionType = InteractionType.SAVE, action = InteractionAction.REMOVE),
            PendingInteractionEntity(id = 3, userId = userId, artifactId = artifactId, interactionType = InteractionType.SAVE, action = InteractionAction.ADD)
        )
        
        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns events

        worker.collapseEvents(userId)

        // Should collapse to the final ADD.
        coVerify(exactly = 1) { pendingInteractionDao.delete(events[0]) }
        coVerify(exactly = 1) { pendingInteractionDao.delete(events[1]) }
        coVerify(exactly = 0) { pendingInteractionDao.delete(events[2]) }
    }

    @Test
    fun `collapseEvents should correctly handle REMOVE-ADD-REMOVE sequence`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val events = listOf(
            PendingInteractionEntity(id = 1, userId = userId, artifactId = artifactId, interactionType = InteractionType.SAVE, action = InteractionAction.REMOVE),
            PendingInteractionEntity(id = 2, userId = userId, artifactId = artifactId, interactionType = InteractionType.SAVE, action = InteractionAction.ADD),
            PendingInteractionEntity(id = 3, userId = userId, artifactId = artifactId, interactionType = InteractionType.SAVE, action = InteractionAction.REMOVE)
        )
        
        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns events

        worker.collapseEvents(userId)

        // Should collapse to the final REMOVE.
        coVerify(exactly = 1) { pendingInteractionDao.delete(events[0]) }
        coVerify(exactly = 1) { pendingInteractionDao.delete(events[1]) }
        coVerify(exactly = 0) { pendingInteractionDao.delete(events[2]) }
    }

    @Test
    fun `collapseEvents should NOT collapse non-collapsible types (COMMENT)`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val events = listOf(
            PendingInteractionEntity(id = 1, userId = userId, artifactId = artifactId, interactionType = InteractionType.COMMENT, action = InteractionAction.ADD, metadata = "Comment 1"),
            PendingInteractionEntity(id = 2, userId = userId, artifactId = artifactId, interactionType = InteractionType.COMMENT, action = InteractionAction.ADD, metadata = "Comment 2")
        )
        
        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns events

        worker.collapseEvents(userId)

        coVerify(exactly = 0) { pendingInteractionDao.delete(any()) }
    }

    @Test
    fun `collapseEvents should preserve order and correctly collapse a mixed queue`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val events = listOf(
            PendingInteractionEntity(id = 1, userId = userId, artifactId = artifactId, interactionType = InteractionType.REACTION, action = InteractionAction.ADD, metadata = "Like"),
            PendingInteractionEntity(id = 2, userId = userId, artifactId = artifactId, interactionType = InteractionType.COMMENT, action = InteractionAction.ADD, metadata = "Comment 1"),
            PendingInteractionEntity(id = 3, userId = userId, artifactId = artifactId, interactionType = InteractionType.REACTION, action = InteractionAction.REMOVE),
            PendingInteractionEntity(id = 4, userId = userId, artifactId = artifactId, interactionType = InteractionType.SAVE, action = InteractionAction.ADD),
            PendingInteractionEntity(id = 5, userId = userId, artifactId = artifactId, interactionType = InteractionType.COMMENT, action = InteractionAction.ADD, metadata = "Comment 2")
        )
        
        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns events

        worker.collapseEvents(userId)

        // REACTION: ADD (1) followed by REMOVE (3) should be a redundant cycle and both deleted.
        coVerify(exactly = 1) { pendingInteractionDao.delete(events[0]) }
        coVerify(exactly = 1) { pendingInteractionDao.delete(events[2]) }
        
        // SAVE: Only one ADD (4), should survive.
        coVerify(exactly = 0) { pendingInteractionDao.delete(events[3]) }
        
        // COMMENT: Both (2) and (5) should survive.
        coVerify(exactly = 0) { pendingInteractionDao.delete(events[1]) }
        coVerify(exactly = 0) { pendingInteractionDao.delete(events[4]) }
    }

    @Test
    fun `collapseEvents should keep only the latest ENGAGEMENT evidence`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val events = listOf(
            PendingInteractionEntity(id = 1, userId = userId, artifactId = artifactId, interactionType = InteractionType.ENGAGEMENT, action = InteractionAction.ADD, metadata = "payload1"),
            PendingInteractionEntity(id = 2, userId = userId, artifactId = artifactId, interactionType = InteractionType.ENGAGEMENT, action = InteractionAction.ADD, metadata = "payload2")
        )
        
        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns events

        worker.collapseEvents(userId)

        coVerify(exactly = 1) { pendingInteractionDao.delete(events[0]) }
        coVerify(exactly = 0) { pendingInteractionDao.delete(events[1]) }
    }
}
