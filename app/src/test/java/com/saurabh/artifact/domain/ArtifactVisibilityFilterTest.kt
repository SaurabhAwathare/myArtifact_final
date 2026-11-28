package com.saurabh.artifact.domain

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.QueryDocumentSnapshot
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.ListenerRegistration
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.IgnoredUserDao
import com.saurabh.artifact.data.local.IgnoredUserEntity
import com.saurabh.artifact.data.local.InteractionAction
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.data.local.PendingInteractionEntity
import com.saurabh.artifact.data.local.ReportedArtifactDao
import com.saurabh.artifact.data.local.SuppressedArtifactDao
import androidx.room.withTransaction
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtifactVisibilityFilterTest {

    private val reportedArtifactDao = mockk<ReportedArtifactDao>(relaxed = true)
    private val suppressedArtifactDao = mockk<SuppressedArtifactDao>(relaxed = true)
    private val ignoredUserDao = mockk<IgnoredUserDao>(relaxed = true)
    private val pendingInteractionDao = mockk<PendingInteractionDao>(relaxed = true)
    private val appDatabase = mockk<AppDatabase>(relaxed = true)
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private lateinit var filter: ArtifactVisibilityFilter

    @Before
    fun setup() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val lambdaSlot = slot<suspend () -> Any?>()
        coEvery { appDatabase.withTransaction(capture(lambdaSlot)) } coAnswers {
            lambdaSlot.captured.invoke()
        }

        filter = ArtifactVisibilityFilter(
            reportedArtifactDao = Lazy { reportedArtifactDao },
            ignoredUserDao = Lazy { ignoredUserDao },
            firestore = firestore,
            pendingInteractionDao = Lazy { pendingInteractionDao },
            appDatabase = Lazy { appDatabase },
            suppressedArtifactDao = Lazy { suppressedArtifactDao }
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `getSuppressedIdsSnapshot should return combined set of reported and suppressed IDs from DAOs`() = runBlocking {
        val userId = "user1"
        val reportedIds = listOf("rep1", "rep2")
        val suppressedIds = listOf("not_me1", "rep2") // overlap rep2
        coEvery { reportedArtifactDao.getReportedArtifactIds(userId) } returns reportedIds
        coEvery { suppressedArtifactDao.getSuppressedArtifactIds(userId) } returns suppressedIds

        val result = filter.getSuppressedIdsSnapshot(userId)

        assertEquals(setOf("rep1", "rep2", "not_me1"), result)
    }

    @Test
    fun `observeSuppressedIds should emit combined set of reported and suppressed IDs from DAO flows`() = runBlocking {
        val userId = "user1"
        val reportedIds = listOf("rep1", "rep2")
        val suppressedIds = listOf("not_me1")
        every { reportedArtifactDao.observeReportedArtifactIds(userId) } returns flowOf(reportedIds)
        every { suppressedArtifactDao.observeSuppressedArtifactIds(userId) } returns flowOf(suppressedIds)

        val result = filter.observeSuppressedIds(userId).first()

        assertEquals(setOf("rep1", "rep2", "not_me1"), result)
    }

    @Test
    fun `hasLocalSuppressionRecords should return true if reported or suppressed artifacts exist for user`() = runBlocking {
        val userId = "user1"
        coEvery { reportedArtifactDao.getReportedArtifactIds(userId) } returns emptyList()
        coEvery { suppressedArtifactDao.getSuppressedArtifactIds(userId) } returns listOf("supp1")

        assertTrue(filter.hasLocalSuppressionRecords(userId))

        coEvery { suppressedArtifactDao.getSuppressedArtifactIds(userId) } returns emptyList()
        assertFalse(filter.hasLocalSuppressionRecords(userId))
    }

    @Test
    fun `syncReportsFromRemote should insert reported artifacts into DAO`() = runBlocking {
        val userId = "user1"
        val artifactId = "art123"
        val scope = this
        
        val collectionRef = mockk<CollectionReference>(relaxed = true)
        val snapshot = mockk<QuerySnapshot>(relaxed = true)
        val change = mockk<DocumentChange>(relaxed = true)
        val doc = mockk<QueryDocumentSnapshot>(relaxed = true)

        every { firestore.collection("users").document(userId).collection("private").document("reports").collection("artifacts") } returns collectionRef
        
        val listenerSlot = slot<EventListener<QuerySnapshot>>()
        every { collectionRef.addSnapshotListener(capture(listenerSlot)) } returns mockk(relaxed = true)

        every { change.type } returns DocumentChange.Type.ADDED
        every { change.document } returns doc
        every { doc.id } returns artifactId
        every { snapshot.documentChanges } returns listOf(change)

        // Start flow collection asynchronously to avoid deadlock
        val job = launch {
            filter.syncReportsFromRemote(userId, scope).collect {}
        }

        // Wait for listener to be registered (it happens when collect starts)
        var attempts = 0
        while (!listenerSlot.isCaptured && attempts < 50) {
            delay(10)
            attempts++
        }

        // Simulate snapshot update
        if (listenerSlot.isCaptured) {
            listenerSlot.captured.onEvent(snapshot, null)
        } else {
            throw AssertionError("Firestore listener was not registered in time")
        }

        // Verify reportedArtifactDao.insert() is called (use timeout as it's launched in scope)
        coVerify(timeout = 2000) { reportedArtifactDao.insert(any()) }
        
        job.cancel()
    }

    @Test
    fun `syncReportsFromRemote should handle PERMISSION_DENIED listener error gracefully without throwing`() = runBlocking {
        val userId = "user1"
        val scope = this

        val collectionRef = mockk<CollectionReference>(relaxed = true)
        every { firestore.collection("users").document(userId).collection("private").document("reports").collection("artifacts") } returns collectionRef

        val listenerSlot = slot<EventListener<QuerySnapshot>>()
        every { collectionRef.addSnapshotListener(capture(listenerSlot)) } returns mockk(relaxed = true)

        val permissionDeniedException = FirebaseFirestoreException(
            "Permission denied",
            FirebaseFirestoreException.Code.PERMISSION_DENIED
        )

        var flowCompletedNormally = false
        val job = launch {
            filter.syncReportsFromRemote(userId, scope).collect {}
            flowCompletedNormally = true
        }

        var attempts = 0
        while (!listenerSlot.isCaptured && attempts < 50) {
            delay(10)
            attempts++
        }

        if (listenerSlot.isCaptured) {
            listenerSlot.captured.onEvent(null, permissionDeniedException)
        } else {
            throw AssertionError("Firestore listener was not registered in time")
        }

        job.join()
        assertEquals(true, flowCompletedNormally)
    }

    @Test
    fun `processIgnoredUserSnapshotChanges suppresses ADDED snapshot when pending REMOVE exists`() = runBlocking {
        val userId = "user1"
        val targetPersonaId = "usr_target_123"

        val change = mockk<DocumentChange>(relaxed = true)
        val doc = mockk<QueryDocumentSnapshot>(relaxed = true)
        every { doc.id } returns targetPersonaId
        every { change.document } returns doc
        every { change.type } returns DocumentChange.Type.ADDED

        // Mock pending REMOVE interaction
        val pendingRemove = PendingInteractionEntity(
            id = 1L,
            userId = userId,
            artifactId = targetPersonaId,
            interactionType = InteractionType.IGNORE,
            action = InteractionAction.REMOVE,
            createdAt = System.currentTimeMillis()
        )
        coEvery {
            pendingInteractionDao.getPendingByType(
                artifactId = targetPersonaId,
                userId = userId,
                type = InteractionType.IGNORE
            )
        } returns listOf(pendingRemove)

        filter.processIgnoredUserSnapshotChanges(userId, listOf(change))

        // Verified: Room ignoredUserDao.insert should NOT be called because pending REMOVE takes precedence
        coVerify(exactly = 0) { ignoredUserDao.insert(any()) }
    }

    @Test
    fun `processIgnoredUserSnapshotChanges inserts into ignoredUserDao when ADDED snapshot arrives without pending REMOVE`() = runBlocking {
        val userId = "user1"
        val targetPersonaId = "usr_target_123"

        val change = mockk<DocumentChange>(relaxed = true)
        val doc = mockk<QueryDocumentSnapshot>(relaxed = true)
        every { doc.id } returns targetPersonaId
        every { change.document } returns doc
        every { change.type } returns DocumentChange.Type.ADDED

        coEvery {
            pendingInteractionDao.getPendingByType(
                artifactId = targetPersonaId,
                userId = userId,
                type = InteractionType.IGNORE
            )
        } returns emptyList()

        filter.processIgnoredUserSnapshotChanges(userId, listOf(change))

        coVerify { ignoredUserDao.insert(match { it.ownerUserId == userId && it.userId == targetPersonaId }) }
    }

    @Test
    fun `processIgnoredUserSnapshotChanges suppresses REMOVED snapshot when pending ADD exists`() = runBlocking {
        val userId = "user1"
        val targetPersonaId = "usr_target_123"

        val change = mockk<DocumentChange>(relaxed = true)
        val doc = mockk<QueryDocumentSnapshot>(relaxed = true)
        every { doc.id } returns targetPersonaId
        every { change.document } returns doc
        every { change.type } returns DocumentChange.Type.REMOVED

        val pendingAdd = PendingInteractionEntity(
            id = 1L,
            userId = userId,
            artifactId = targetPersonaId,
            interactionType = InteractionType.IGNORE,
            action = InteractionAction.ADD,
            createdAt = System.currentTimeMillis()
        )
        coEvery {
            pendingInteractionDao.getPendingByType(
                artifactId = targetPersonaId,
                userId = userId,
                type = InteractionType.IGNORE
            )
        } returns listOf(pendingAdd)

        filter.processIgnoredUserSnapshotChanges(userId, listOf(change))

        // Verified: Room ignoredUserDao.delete should NOT be called because pending ADD takes precedence
        coVerify(exactly = 0) { ignoredUserDao.delete(any(), any()) }
    }

    @Test
    fun `processIgnoredUserSnapshotChanges deletes from ignoredUserDao when REMOVED snapshot arrives without pending ADD`() = runBlocking {
        val userId = "user1"
        val targetPersonaId = "usr_target_123"

        val change = mockk<DocumentChange>(relaxed = true)
        val doc = mockk<QueryDocumentSnapshot>(relaxed = true)
        every { doc.id } returns targetPersonaId
        every { change.document } returns doc
        every { change.type } returns DocumentChange.Type.REMOVED

        coEvery {
            pendingInteractionDao.getPendingByType(
                artifactId = targetPersonaId,
                userId = userId,
                type = InteractionType.IGNORE
            )
        } returns emptyList()

        filter.processIgnoredUserSnapshotChanges(userId, listOf(change))

        coVerify { ignoredUserDao.delete(targetPersonaId, userId) }
    }

    @Test
    fun `syncIgnoredUsersFromRemote recovers after listener error without throwing`() = runBlocking {
        val userId = "user1"
        val testScope = CoroutineScope(Dispatchers.Default + Job())

        val collectionRef = mockk<CollectionReference>(relaxed = true)
        every {
            firestore.collection("users").document(userId)
                .collection("private").document("ignored_users")
                .collection("users")
        } returns collectionRef

        val listenerSlot = slot<EventListener<QuerySnapshot>>()
        every { collectionRef.addSnapshotListener(capture(listenerSlot)) } returns mockk(relaxed = true)

        try {
            val job = testScope.launch {
                filter.syncIgnoredUsersFromRemote(userId, testScope).collect {}
            }

            var attempts = 0
            while (!listenerSlot.isCaptured && attempts < 50) {
                delay(10)
                attempts++
            }

            assertTrue(listenerSlot.isCaptured)

            // Trigger listener error
            val error = FirebaseFirestoreException("Transient network issue", FirebaseFirestoreException.Code.UNAVAILABLE)
            listenerSlot.captured.onEvent(null, error)

            // The flow job should remain active because syncIgnoredUsersFromRemote uses retryJob with backoff
            assertTrue(job.isActive)
        } finally {
            testScope.cancel()
        }
    }
}
