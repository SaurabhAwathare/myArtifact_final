package com.saurabh.artifact.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import com.google.firebase.firestore.FirebaseFirestoreException
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.data.remote.model.CommentPayload
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.*
import com.saurabh.artifact.security.AppCheckHealthResult
import com.saurabh.artifact.security.AppCheckStateTracker
import com.saurabh.artifact.startup.StartupCoordinator
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InteractionSyncWorkerCommentTest {

    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val pendingInteractionDao: PendingInteractionDao = mockk(relaxed = true)
    private val deadLetterDao: DeadLetterInteractionDao = mockk(relaxed = true)
    private val reactionRepository: ReactionRepository = mockk()
    private val artifactLibraryRepository: ArtifactLibraryRepository = mockk()
    private val engagementRepository: EngagementRepository = mockk(relaxed = true)
    private val firestoreEngagementRepository: FirestoreEngagementRepository = mockk()
    private val commentRepository: CommentRepository = mockk(relaxed = true)
    private val userRepository: UserRepository = mockk()
    private val startupCoordinator: StartupCoordinator = mockk(relaxed = true)
    private val appCheckStateTracker: AppCheckStateTracker = mockk()
    private val userSessionManager: UserSessionManager = mockk(relaxed = true)

    private lateinit var errorClassifier: InteractionErrorClassifier
    private lateinit var worker: InteractionSyncWorker
    private val testDispatcher = StandardTestDispatcher()

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        mockkStatic(FirebaseAuth::class)
        val auth = mockk<FirebaseAuth>()
        val user = mockk<FirebaseUser>()
        val tokenResult = mockk<GetTokenResult>()

        every { FirebaseAuth.getInstance() } returns auth
        every { auth.currentUser } returns user
        every { user.uid } returns "test-user"
        every { tokenResult.claims } returns mapOf("activeSessionId" to "test-session")
        coEvery { user.getIdToken(any()) } returns Tasks.forResult(tokenResult)
        every { userSessionManager.localSessionId } returns flowOf("test-session")

        coEvery { appCheckStateTracker.probeHealth() } returns AppCheckHealthResult.Healthy(mockk(relaxed = true))
        errorClassifier = InteractionErrorClassifier(appCheckStateTracker)

        worker = InteractionSyncWorker(
            context,
            workerParams,
            Lazy { pendingInteractionDao },
            Lazy { deadLetterDao },
            reactionRepository,
            artifactLibraryRepository,
            engagementRepository,
            firestoreEngagementRepository,
            commentRepository,
            userRepository,
            startupCoordinator,
            errorClassifier,
            Lazy { mockk<AuthRepository>(relaxed = true) },
            Lazy { userSessionManager }
        )
    }

    @Test
    fun `processInteraction with COMMENT uses stable ID from metadata and retains record for observation`() = runTest {
        val commentId = UUID.randomUUID().toString()
        val artifactId = "art-1"
        val comment = Comment(id = commentId, artifactId = artifactId, text = "Hello")
        val commentJson = json.encodeToString(CommentPayload.serializer(), CommentPayload.fromDomain(comment))
        
        val interaction = PendingInteractionEntity(
            userId = "test-user",
            artifactId = artifactId,
            interactionType = InteractionType.COMMENT,
            action = InteractionAction.ADD,
            metadata = commentJson
        )

        val pendingList = mutableListOf(interaction)
        coEvery { pendingInteractionDao.getPendingForUser("test-user") } answers { pendingList.toList() }

        coEvery { commentRepository.createComment(any()) } returns Result.success(comment)
        coEvery { commentRepository.observeIntentStatus(any(), any(), any()) } returns Unit
        
        val result = worker.doWork()
        
        assertEquals(ListenableWorker.Result.success(), result)
        
        // Verify same comment with same ID was submitted as private intent
        coVerify { commentRepository.createComment(match { it.id == commentId && it.text == "Hello" }) }
        // Verify record was marked intentSubmitted and updated in Room
        coVerify { pendingInteractionDao.insert(match { it.metadata?.contains("\"intentSubmitted\":true") == true }) }
        // Verify snapshot observation was initiated
        coVerify { commentRepository.observeIntentStatus("test-user", commentId, artifactId) }
    }

    @Test
    fun `R035 - COMMENT retries on PERMISSION_DENIED (locked artifact)`() = runTest {
        val commentId = UUID.randomUUID().toString()
        val comment = Comment(id = commentId, artifactId = "art-1", text = "Hello")
        val commentJson = json.encodeToString(CommentPayload.serializer(), CommentPayload.fromDomain(comment))
        
        val interaction = PendingInteractionEntity(
            userId = "test-user",
            artifactId = "art-1",
            interactionType = InteractionType.COMMENT,
            action = InteractionAction.ADD,
            metadata = commentJson
        )

        val pendingList = mutableListOf(interaction)
        coEvery { pendingInteractionDao.getPendingForUser("test-user") } answers { pendingList.toList() }

        val lockedError = FirebaseFirestoreException("Locked", FirebaseFirestoreException.Code.PERMISSION_DENIED)

        coEvery { commentRepository.createComment(any()) } returns Result.failure(lockedError)
        
        val result = worker.doWork()
        
        assertEquals(ListenableWorker.Result.retry(), result)
        
        coVerify { 
            pendingInteractionDao.insert(match { 
                it.lastError == "Locked" && it.retryCount == 1 
            }) 
        }
    }

    @Test
    fun `R035 - COMMENT retries on AppError PermissionDenied`() = runTest {
        val commentId = UUID.randomUUID().toString()
        val comment = Comment(id = commentId, artifactId = "art-1", text = "Hello")
        val commentJson = json.encodeToString(CommentPayload.serializer(), CommentPayload.fromDomain(comment))
        
        val interaction = PendingInteractionEntity(
            userId = "test-user",
            artifactId = "art-1",
            interactionType = InteractionType.COMMENT,
            action = InteractionAction.ADD,
            metadata = commentJson
        )

        val pendingList = mutableListOf(interaction)
        coEvery { pendingInteractionDao.getPendingForUser("test-user") } answers { pendingList.toList() }

        val domainPermissionError = AppError.PermissionDenied("Permission denied")

        coEvery { commentRepository.createComment(any()) } returns Result.failure(domainPermissionError)
        
        val result = worker.doWork()
        
        assertEquals(ListenableWorker.Result.retry(), result)
        
        coVerify { 
            pendingInteractionDao.insert(match { 
                it.lastError == "Permission denied" && it.retryCount == 1 
            }) 
        }
    }

    @Test
    fun `account switching - worker succeeds with empty queue when UID mismatch`() = runTest {
        val auth = FirebaseAuth.getInstance()
        val differentUser = mockk<FirebaseUser>()
        val tokenResult = mockk<GetTokenResult>()
        every { differentUser.uid } returns "different-user"
        every { tokenResult.claims } returns mapOf("activeSessionId" to "different-session")
        coEvery { differentUser.getIdToken(any()) } returns Tasks.forResult(tokenResult)
        every { auth.currentUser } returns differentUser
        every { userSessionManager.localSessionId } returns flowOf("different-session")
        
        val result = worker.doWork()
        
        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { commentRepository.createComment(any()) }
    }
}
