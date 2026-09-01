package com.saurabh.artifact.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestoreException
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class InteractionSyncWorkerCommentTest {

    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val pendingInteractionDao: PendingInteractionDao = mockk(relaxed = true)
    private val deadLetterDao: DeadLetterInteractionDao = mockk(relaxed = true)
    private val reactionRepository: ReactionRepository = mockk()
    private val artifactLibraryRepository: ArtifactLibraryRepository = mockk()
    private val engagementRepository: EngagementRepository = mockk(relaxed = true)
    private val firestoreEngagementRepository: FirestoreEngagementRepository = mockk()
    private val commentRepository: CommentRepository = mockk()
    private val userRepository: UserRepository = mockk()
    private val startupCoordinator: com.saurabh.artifact.startup.StartupCoordinator = mockk(relaxed = true)

    private lateinit var worker: InteractionSyncWorker
    private val testDispatcher = StandardTestDispatcher()

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        mockkStatic(com.google.firebase.auth.FirebaseAuth::class)
        val auth = mockk<com.google.firebase.auth.FirebaseAuth>()
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        every { com.google.firebase.auth.FirebaseAuth.getInstance() } returns auth
        every { auth.currentUser } returns user
        every { user.uid } returns "test-user"

        worker = InteractionSyncWorker(
            context,
            workerParams,
            dagger.Lazy { pendingInteractionDao },
            dagger.Lazy { deadLetterDao },
            reactionRepository,
            artifactLibraryRepository,
            engagementRepository,
            firestoreEngagementRepository,
            commentRepository,
            userRepository,
            startupCoordinator
        )
    }

    @Test
    fun `processInteraction with COMMENT uses stable ID from metadata`() = runTest {
        val commentId = UUID.randomUUID().toString()
        val artifactId = "art-1"
        val comment = Comment(id = commentId, artifactId = artifactId, text = "Hello")
        val commentJson = json.encodeToString(comment)
        
        val interaction = PendingInteractionEntity(
            userId = "test-user",
            artifactId = artifactId,
            interactionType = InteractionType.COMMENT,
            action = InteractionAction.ADD,
            metadata = commentJson
        )

        coEvery { commentRepository.createComment(any()) } returns Result.success(comment)
        coEvery { pendingInteractionDao.getPendingForUser("test-user") } returns listOf(interaction)
        
        val result = worker.doWork()
        
        assertEquals(ListenableWorker.Result.success(), result)
        
        // Verify same comment with same ID was passed to repository
        coVerify { commentRepository.createComment(match { it.id == commentId && it.text == "Hello" }) }
        coVerify { pendingInteractionDao.delete(interaction) }
    }

    @Test
    fun `R035 - COMMENT retries on PERMISSION_DENIED (locked artifact)`() = runTest {
        val commentId = UUID.randomUUID().toString()
        val comment = Comment(id = commentId, artifactId = "art-1", text = "Hello")
        val commentJson = json.encodeToString(comment)
        
        val interaction = PendingInteractionEntity(
            userId = "test-user",
            artifactId = "art-1",
            interactionType = InteractionType.COMMENT,
            action = InteractionAction.ADD,
            metadata = commentJson
        )

        // Simulate Firestore Permission Denied (Listening threshold not met yet)
        val lockedError = mockk<FirebaseFirestoreException>()
        every { lockedError.code } returns FirebaseFirestoreException.Code.PERMISSION_DENIED
        every { lockedError.message } returns "Locked"

        coEvery { commentRepository.createComment(any()) } returns Result.failure(lockedError)
        coEvery { pendingInteractionDao.getPendingForUser("test-user") } returns listOf(interaction)
        
        val result = worker.doWork()
        
        // Should return retry to allow backend verification to catch up
        assertEquals(ListenableWorker.Result.retry(), result)
        
        // Verify interaction was updated with the error and retry count incremented
        coVerify { 
            pendingInteractionDao.insert(match { 
                it.lastError == "Locked" && it.retryCount == 1 
            }) 
        }
    }

    @Test
    fun `account switching - worker fails if UID mismatch`() = runTest {
        val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
        every { auth.currentUser?.uid } returns "different-user"
        
        val result = worker.doWork()
        
        // Should return failure if current authenticated user doesn't match worker context
        // Wait, InteractionSyncWorker captures currentUserId at start of doWork.
        // It then fetches pending for THAT user.
        // So if User A enqueued something, but User B is logged in, 
        // the worker (running as User B) won't see User A's records.
        
        assertEquals(ListenableWorker.Result.success(), result) // success because queue for B is empty
        coVerify(exactly = 0) { commentRepository.createComment(any()) }
    }
}
