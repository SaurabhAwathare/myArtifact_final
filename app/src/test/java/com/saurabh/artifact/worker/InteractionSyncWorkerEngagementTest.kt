package com.saurabh.artifact.worker

import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.domain.review.EngagementSyncPayload
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.ReactionRepository
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.repository.EngagementRepository
import com.saurabh.artifact.util.ArtifactLogger
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.After
import org.junit.Test
import androidx.work.WorkerParameters
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class InteractionSyncWorkerEngagementTest {
    private val pendingInteractionDao = mockk<PendingInteractionDao>(relaxed = true)
    private val deadLetterInteractionDao = mockk<DeadLetterInteractionDao>(relaxed = true)
    private val reactionRepository = mockk<ReactionRepository>(relaxed = true)
    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val engagementRepository = mockk<EngagementRepository>(relaxed = true)
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val gson = Gson()
    private val workerParams = mockk<WorkerParameters>(relaxed = true)

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
            gson = gson
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `worker should call engagementRepository syncEngagementToFirestore`() = runTest(timeout = 10.seconds) {
        val userId = "user123"
        val artifactId = "art123"
        val payload = EngagementSyncPayload(
            artifactId = artifactId,
            lastPositionMs = 5000L,
            furthestPositionMs = 6000L,
            durationMs = 10000L,
            hasReachedEnd = false,
            coverage = "AQID", // Base64 for [1, 2, 3]
            lastUpdated = 123456789L
        )
        val interaction = PendingInteractionEntity(
            id = 1,
            userId = userId,
            artifactId = artifactId,
            interactionType = InteractionType.ENGAGEMENT,
            action = InteractionAction.ADD,
            metadata = gson.toJson(payload)
        )

        coEvery { pendingInteractionDao.getPendingForUser(userId) } returns listOf(interaction)
        coEvery { engagementRepository.syncEngagementToFirestore(any(), any()) } returns Result.success(Unit)
        
        // Mock Auth to return userId
        mockkStatic(com.google.firebase.auth.FirebaseAuth::class)
        val auth = mockk<com.google.firebase.auth.FirebaseAuth>()
        val firebaseUser = mockk<com.google.firebase.auth.FirebaseUser>()
        every { com.google.firebase.auth.FirebaseAuth.getInstance() } returns auth
        every { auth.currentUser } returns firebaseUser
        every { firebaseUser.uid } returns userId

        worker.doWork()

        coVerify { 
            engagementRepository.syncEngagementToFirestore(userId, match { 
                it.artifactId == payload.artifactId && it.lastPositionMs == payload.lastPositionMs
            })
        }
        
        coVerify { pendingInteractionDao.delete(any()) }
    }
}
