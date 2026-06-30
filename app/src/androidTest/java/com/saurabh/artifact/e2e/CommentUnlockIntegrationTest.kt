package com.saurabh.artifact.e2e

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.gson.Gson
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.domain.review.GetEngagementStateUseCase
import com.saurabh.artifact.model.EngagementStatus
import com.saurabh.artifact.repository.*
import com.saurabh.artifact.worker.InteractionSyncWorker
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.BitSet
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CommentUnlockIntegrationTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var firestore: FirebaseFirestore

    @Inject
    lateinit var auth: FirebaseAuth

    @Inject
    lateinit var functions: FirebaseFunctions

    @Inject
    lateinit var engagementRepository: EngagementRepository

    @Inject
    lateinit var commentRepository: CommentRepository

    @Inject
    lateinit var getEngagementStateUseCase: GetEngagementStateUseCase

    @Inject
    lateinit var pendingInteractionDao: PendingInteractionDao

    @Inject
    lateinit var deadLetterInteractionDao: DeadLetterInteractionDao

    @Inject
    lateinit var reactionRepository: ReactionRepository

    @Inject
    lateinit var artifactRepository: ArtifactRepository

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var engagementDao: EngagementDao

    @Inject
    lateinit var gson: Gson

    private lateinit var context: Context
    private val testArtifactId = "test_artifact_happy"
    private val artifactOwnerId = "owner_456"

    @Before
    fun setup() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()

        // Connect to Emulators
        firestore.useEmulator("10.0.2.2", 8080)
        auth.useEmulator("10.0.2.2", 9099)
        functions.useEmulator("10.0.2.2", 5001)

        runBlocking {
            if (auth.currentUser == null) {
                auth.signInAnonymously().await()
            }
            setupTestArtifact(testArtifactId, artifactOwnerId)
        }
    }

    private suspend fun setupTestArtifact(artifactId: String, ownerId: String) {
        val artifactData = mapOf(
            "id" to artifactId,
            "userId" to ownerId,
            "title" to "Integration Test Artifact",
            "durationMs" to 10000L,
            "status" to "ACTIVE"
        )
        firestore.collection("artifacts").document(artifactId).set(artifactData).await()
    }

    private fun triggerSync() = runBlocking {
        // Manual construction of Worker to avoid AssistedFactory complexity in lean tests
        val workerParams = androidx.work.testing.TestWorkerBuilder.from(context, InteractionSyncWorker::class.java).build().workerParameters
        
        val manualWorker = InteractionSyncWorker(
            appContext = context,
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
        val syncResult = manualWorker.doWork()
        assertEquals(ListenableWorker.Result.success(), syncResult)
    }

    @Test
    fun testHappyPathUnlock() = runBlocking {
        val artifactId = testArtifactId
        val userId = auth.currentUser!!.uid

        // 1. Simulate 95% Playback Reached
        val coverage = BitSet(100).apply { set(0, 96) }
        val evidence = EngagementEvidence(
            artifactId = artifactId,
            versionTag = "v1",
            durationMs = 10000L,
            coverage = coverage,
            lastPositionMs = 9600L,
            furthestPositionMs = 9600L,
            hasReachedEnd = true
        )

        // 2. Persist Locally
        engagementRepository.saveEngagement(evidence)
        
        // 3. Trigger Synchronization
        triggerSync()
        
        // 4. Verify Final State via UseCase (wait for CF processing)
        withTimeout(15000) {
            getEngagementStateUseCase.execute(artifactId).first { it == EngagementStatus.UNLOCKED }
        }
        
        // 5. Verify Comments Loading
        setupTestComment(artifactId, artifactOwnerId)
        val ownComments = commentRepository.observeOwnComments(artifactId, userId).first()
        val sharedComments = commentRepository.observeSharedComments(artifactId).first()
        
        assertTrue("Own comments should be observable", ownComments != null)
        assertTrue("Should load the owner's comment as shared", sharedComments.any { it.artifactId == artifactId })
    }

    private suspend fun setupTestComment(artifactId: String, ownerId: String) {
        val commentId = "test_comment_1"
        val comment = mapOf(
            "id" to commentId,
            "artifactId" to artifactId,
            "authorId" to ownerId,
            "artifactOwnerId" to ownerId,
            "content" to "Owner Reflection",
            "visibilityLayer" to "RESONANCE",
            "moderationState" to "APPROVED",
            "createdAt" to com.google.firebase.Timestamp.now()
        )
        firestore.collection("comments").document(commentId).set(comment).await()
    }

    @Test
    fun testBelowThresholdStaysLocked() = runBlocking {
        val artifactId = "test_artifact_locked"
        setupTestArtifact(artifactId, artifactOwnerId)

        // 1. Simulate 50% Playback
        val coverage = BitSet(100).apply { set(0, 50) }
        val evidence = EngagementEvidence(
            artifactId = artifactId,
            versionTag = "v1",
            durationMs = 10000L,
            coverage = coverage,
            lastPositionMs = 5000L,
            furthestPositionMs = 5000L,
            hasReachedEnd = false
        )

        engagementRepository.saveEngagement(evidence)
        
        // 2. Trigger Sync
        triggerSync()

        // 3. Verify status remains LOCKED
        val status = getEngagementStateUseCase.execute(artifactId).first()
        assertEquals(EngagementStatus.LOCKED, status)
    }

    @Test
    fun testCloudFunctionFailureGraceful() = runBlocking {
        val artifactId = "test_artifact_cf_fail"
        val userId = auth.currentUser!!.uid
        setupTestArtifact(artifactId, artifactOwnerId)

        // 1. Send Malformed Data directly to Firestore
        val updates = mapOf(
            "userId" to userId,
            "artifactId" to artifactId,
            "coverage" to "NOT_A_BLOB", 
            "updatedAt" to System.currentTimeMillis()
        )
        firestore.collection("users").document(userId)
            .collection("engagement").document(artifactId).set(updates).await()

        // 2. Wait for CF
        delay(2000)

        // 3. Verify no crash and user remains locked
        val status = getEngagementStateUseCase.execute(artifactId).first()
        assertTrue("Status should not be UNLOCKED", status != EngagementStatus.UNLOCKED)
    }

    @Test
    fun testOfflineRecovery() = runBlocking {
        val artifactId = "test_artifact_offline"
        setupTestArtifact(artifactId, artifactOwnerId)

        // 1. Reach threshold offline
        val coverage = BitSet(100).apply { set(0, 100) }
        val evidence = EngagementEvidence(
            artifactId = artifactId,
            versionTag = "v1",
            durationMs = 10000L,
            coverage = coverage,
            lastPositionMs = 10000L,
            furthestPositionMs = 10000L,
            hasReachedEnd = true
        )
        engagementRepository.saveEngagement(evidence)

        // 2. "Reconnect" -> Trigger Sync
        triggerSync()

        // 3. Verify eventual UNLOCK
        withTimeout(15000) {
            getEngagementStateUseCase.execute(artifactId).first { it == EngagementStatus.UNLOCKED }
        }
    }

    @Test
    fun testReplayRegression() = runBlocking {
        val artifactId = "test_artifact_replay"
        setupTestArtifact(artifactId, artifactOwnerId)

        // 1. Initial Unlock
        val evidence = EngagementEvidence(
            artifactId = artifactId, 
            versionTag = "v1",
            durationMs = 10000L, 
            coverage = BitSet(100).apply { set(0, 100) }, 
            hasReachedEnd = true
        )
        engagementRepository.saveEngagement(evidence)
        triggerSync()

        withTimeout(10000) {
            getEngagementStateUseCase.execute(artifactId).first { it == EngagementStatus.UNLOCKED }
        }

        // 2. Replay
        engagementRepository.saveEngagement(evidence)
        triggerSync()
        
        val status = getEngagementStateUseCase.execute(artifactId).first { it == EngagementStatus.UNLOCKED }
        assertEquals(EngagementStatus.UNLOCKED, status)
    }
}
