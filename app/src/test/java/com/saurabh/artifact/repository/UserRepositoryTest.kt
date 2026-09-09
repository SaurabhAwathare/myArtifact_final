package com.saurabh.artifact.repository

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import com.saurabh.artifact.data.local.InteractionAction
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.data.local.PendingInteractionEntity
import com.saurabh.artifact.data.local.UserDao
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.domain.IdentityProtectionPolicy
import com.saurabh.artifact.domain.auth.RegistrationCoordinator
import com.saurabh.artifact.model.User
import com.saurabh.artifact.worker.InteractionSyncWorker
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UserRepositoryTest {
    private val context = mockk<Context>()
    private val auth = mockk<FirebaseAuth>()
    private val firestore = mockk<FirebaseFirestore>()
    private val userDao = mockk<UserDao>(relaxed = true)
    private val pendingInteractionDao = mockk<PendingInteractionDao>(relaxed = true)
    private val identityPolicy = mockk<IdentityProtectionPolicy>()
    private val regCoordinator = mockk<RegistrationCoordinator>()
    private val logger = mockk<DiagnosticLogger>(relaxed = true)

    private val mockColl = mockk<CollectionReference>(relaxed = true)
    private val mockDoc = mockk<DocumentReference>(relaxed = true)

    private lateinit var repository: UserRepository

    @Before
    fun setup() {
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        
        val mockSnapshot = mockk<DocumentSnapshot>(relaxed = true)
        
        coEvery { any<Task<DocumentSnapshot>>().await() } returns mockSnapshot
        coEvery { any<Task<QuerySnapshot>>().await() } returns mockk(relaxed = true)
        coEvery { any<Task<Void>>().await() } returns mockk(relaxed = true)
        coEvery { any<Task<Any>>().await() } returns mockk(relaxed = true)

        every { mockDoc.get() } returns mockk(relaxed = true)
        every { mockColl.document(any()) } returns mockDoc
        every { mockColl.get() } returns mockk(relaxed = true)
        
        every { firestore.collection(any()) } returns mockColl
        
        repository = UserRepository(
            context, auth, firestore,
            Lazy { userDao },
            identityPolicy,
            Lazy { regCoordinator },
            Lazy { pendingInteractionDao },
            mockk(relaxed = true),
            logger
        )
    }

    @Test
    fun `getOrCreateProfile repairs Zombie Profile missing identity fields`() = runBlocking {
        val userId = "user123"
        val firebaseUser = mockk<FirebaseUser>()
        every { firebaseUser.uid } returns userId
        every { firebaseUser.email } returns "test@example.com"
        every { firebaseUser.displayName } returns "Test User"
        every { firebaseUser.reload() } returns mockk(relaxed = true)
        every { auth.currentUser } returns firebaseUser

        val userRef = mockk<DocumentReference>(relaxed = true)
        val snapshot = mockk<DocumentSnapshot>()
        
        val incompleteUser = User(id = userId, anonymousName = "", anonymousId = "", anonymousSigil = "", sigilSeed = "")
        every { snapshot.exists() } returns true
        every { snapshot.toObject(User::class.java) } returns incompleteUser
        every { snapshot.id } returns userId
        every { snapshot.get(any<String>()) } returns null
        
        val transaction = mockk<Transaction>(relaxed = true)
        every { transaction.get(any<DocumentReference>()) } returns snapshot
        
        every { firestore.runTransaction<Any>(any()) } answers {
            val block = firstArg<Transaction.Function<Any>>()
            val result = block.apply(transaction)
            val task = mockk<Task<Any>>(relaxed = true)
            coEvery { task.await() } returns (result ?: mockk())
            task
        }
        
        val result = repository.getOrCreateProfile()
        
        val repairedUser = result.getOrNull()?.user
        assertTrue("Repair should have occurred", repairedUser != null && repairedUser.anonymousName.isNotEmpty())
        
        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `getOrCreateProfile signs out on FirebaseAuthInvalidUserException during reload`() = runBlocking {
        val userId = "user123"
        val firebaseUser = mockk<FirebaseUser>()
        every { firebaseUser.uid } returns userId
        
        val reloadTask = mockk<Task<Void>>(relaxed = true)
        every { firebaseUser.reload() } returns reloadTask
        
        val authException = mockk<FirebaseAuthInvalidUserException>(relaxed = true)
        coEvery { reloadTask.await() } throws authException
        
        every { auth.currentUser } returns firebaseUser
        every { auth.signOut() } just Runs

        repository.getOrCreateProfile()

        verify(exactly = 1) { auth.signOut() }
        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `emergencyIdentityReset with severRelationships adds flag to identityMetadata`() = runBlocking {
        val userId = "user123"
        val userRef = mockk<DocumentReference>(relaxed = true)
        
        val user = User(id = userId, anonymousName = "OldName")
        val snapshot = mockk<DocumentSnapshot>()
        every { snapshot.toObject(User::class.java) } returns user
        every { snapshot.exists() } returns true
        every { snapshot.id } returns userId
        
        val transaction = mockk<Transaction>(relaxed = true)
        every { transaction.get(any<DocumentReference>()) } returns snapshot
        
        val updates = mutableListOf<Map<String, Any>>()
        every { transaction.update(any(), capture(updates)) } returns transaction
        
        every { firestore.runTransaction<Any>(any()) } answers {
            val block = firstArg<Transaction.Function<Any>>()
            val result = block.apply(transaction)
            val task = mockk<Task<Any>>(relaxed = true)
            coEvery { task.await() } returns (result ?: mockk())
            task
        }
        
        repository.emergencyIdentityReset(userId, severRelationships = true)

        val capturedMap = updates.find { it.containsKey("identityMetadata.severRelationships") }
        assertTrue("Captured maps should contain severRelationships", capturedMap != null)
        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `resonateWithUser fails when currentUserId or targetUserId is blank`() = runBlocking {
        val res1 = repository.resonateWithUser("", "target123")
        assertTrue(res1.isFailure)

        val res2 = repository.resonateWithUser("user123", "   ")
        assertTrue(res2.isFailure)
    }

    @Test
    fun `resonateWithUser fails when currentUserId equals targetUserId`() = runBlocking {
        val res = repository.resonateWithUser("user123", "user123")
        assertTrue(res.isFailure)
        assertEquals("Cannot resonate with yourself", res.exceptionOrNull()?.message)
    }

    @Test
    fun `resonateWithUser deletes prior pending follow and enqueues ADD interaction`() = runBlocking {
        mockkObject(InteractionSyncWorker.Companion)
        every { InteractionSyncWorker.enqueue(any()) } just Runs

        val capturedPending = slot<PendingInteractionEntity>()
        coEvery { pendingInteractionDao.deleteByType("target123", "user123", InteractionType.FOLLOW) } just Runs
        coEvery { pendingInteractionDao.insert(capture(capturedPending)) } just Runs

        val result = repository.resonateWithUser("  user123  ", "  target123  ")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { pendingInteractionDao.deleteByType("target123", "user123", InteractionType.FOLLOW) }
        coVerify(exactly = 1) { pendingInteractionDao.insert(any()) }
        verify(exactly = 1) { InteractionSyncWorker.enqueue(context) }

        assertEquals("user123", capturedPending.captured.userId)
        assertEquals("target123", capturedPending.captured.artifactId)
        assertEquals(InteractionType.FOLLOW, capturedPending.captured.interactionType)
        assertEquals(InteractionAction.ADD, capturedPending.captured.action)

        unmockkObject(InteractionSyncWorker.Companion)
    }

    @Test
    fun `resonateWithUser returns failure when DAO operation throws exception`() = runBlocking {
        coEvery { pendingInteractionDao.deleteByType(any(), any(), any()) } throws RuntimeException("DB error")

        val result = repository.resonateWithUser("user123", "target123")

        assertTrue(result.isFailure)
        assertEquals("DB error", result.exceptionOrNull()?.message)
    }

    @Test
    fun `stopResonatingWithUser fails when currentUserId or targetUserId is blank`() = runBlocking {
        val res1 = repository.stopResonatingWithUser("", "target123")
        assertTrue(res1.isFailure)

        val res2 = repository.stopResonatingWithUser("user123", "   ")
        assertTrue(res2.isFailure)
    }

    @Test
    fun `stopResonatingWithUser fails when currentUserId equals targetUserId`() = runBlocking {
        val res = repository.stopResonatingWithUser("user123", "user123")
        assertTrue(res.isFailure)
        assertEquals("Cannot resonate with yourself", res.exceptionOrNull()?.message)
    }

    @Test
    fun `stopResonatingWithUser deletes prior pending follow and enqueues REMOVE interaction`() = runBlocking {
        mockkObject(InteractionSyncWorker.Companion)
        every { InteractionSyncWorker.enqueue(any()) } just Runs

        val capturedPending = slot<PendingInteractionEntity>()
        coEvery { pendingInteractionDao.deleteByType("target123", "user123", InteractionType.FOLLOW) } just Runs
        coEvery { pendingInteractionDao.insert(capture(capturedPending)) } just Runs

        val result = repository.stopResonatingWithUser("user123", "target123")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { pendingInteractionDao.deleteByType("target123", "user123", InteractionType.FOLLOW) }
        coVerify(exactly = 1) { pendingInteractionDao.insert(any()) }
        verify(exactly = 1) { InteractionSyncWorker.enqueue(context) }

        assertEquals("user123", capturedPending.captured.userId)
        assertEquals("target123", capturedPending.captured.artifactId)
        assertEquals(InteractionType.FOLLOW, capturedPending.captured.interactionType)
        assertEquals(InteractionAction.REMOVE, capturedPending.captured.action)

        unmockkObject(InteractionSyncWorker.Companion)
    }

    @Test
    fun `syncFollowToFirestore fails when currentUserId or targetAnonymousId is blank`() = runBlocking {
        val res1 = repository.syncFollowToFirestore("", "target123")
        assertTrue(res1.isFailure)

        val res2 = repository.syncFollowToFirestore("user123", "  ")
        assertTrue(res2.isFailure)
    }

    @Test
    fun `syncFollowToFirestore sets intent document in Firestore private follow collection`() = runBlocking {
        val userDoc = mockk<DocumentReference>(relaxed = true)
        val privateColl = mockk<CollectionReference>(relaxed = true)
        val intentsDoc = mockk<DocumentReference>(relaxed = true)
        val followColl = mockk<CollectionReference>(relaxed = true)
        val intentDoc = mockk<DocumentReference>(relaxed = true)

        every { mockColl.document("user123") } returns userDoc
        every { userDoc.collection("private") } returns privateColl
        every { privateColl.document("intents") } returns intentsDoc
        every { intentsDoc.collection("follow") } returns followColl
        every { followColl.document("target123") } returns intentDoc

        val setMapSlot = slot<Map<String, Any>>()
        val mockTask = mockk<Task<Void>>(relaxed = true)
        every { intentDoc.set(capture(setMapSlot)) } returns mockTask
        coEvery { mockTask.await() } returns mockk(relaxed = true)

        val result = repository.syncFollowToFirestore("user123", "target123")

        assertTrue(result.isSuccess)
        assertEquals("target123", setMapSlot.captured["targetAnonymousId"])
        assertEquals("FOLLOW", setMapSlot.captured["action"])
        assertEquals(1, setMapSlot.captured["version"])
    }

    @Test
    fun `syncUnfollowFromFirestore fails when currentUserId or targetAnonymousId is blank`() = runBlocking {
        val res1 = repository.syncUnfollowFromFirestore("", "target123")
        assertTrue(res1.isFailure)

        val res2 = repository.syncUnfollowFromFirestore("user123", "  ")
        assertTrue(res2.isFailure)
    }

    @Test
    fun `syncUnfollowFromFirestore deletes intent document in Firestore private follow collection`() = runBlocking {
        val userDoc = mockk<DocumentReference>(relaxed = true)
        val privateColl = mockk<CollectionReference>(relaxed = true)
        val intentsDoc = mockk<DocumentReference>(relaxed = true)
        val followColl = mockk<CollectionReference>(relaxed = true)
        val intentDoc = mockk<DocumentReference>(relaxed = true)

        every { mockColl.document("user123") } returns userDoc
        every { userDoc.collection("private") } returns privateColl
        every { privateColl.document("intents") } returns intentsDoc
        every { intentsDoc.collection("follow") } returns followColl
        every { followColl.document("target123") } returns intentDoc

        val mockTask = mockk<Task<Void>>(relaxed = true)
        every { intentDoc.delete() } returns mockTask
        coEvery { mockTask.await() } returns mockk(relaxed = true)

        val result = repository.syncUnfollowFromFirestore("user123", "target123")

        assertTrue(result.isSuccess)
        verify(exactly = 1) { intentDoc.delete() }
    }

    @Test
    fun `isResonating returns false when inputs are blank`() = runBlocking {
        val res = repository.isResonating("", "target123")
        assertFalse(res)
    }

    @Test
    fun `isResonating returns true when modern resonance_out document exists`() = runBlocking {
        val userDoc = mockk<DocumentReference>(relaxed = true)
        val resonanceColl = mockk<CollectionReference>(relaxed = true)
        val targetDoc = mockk<DocumentReference>(relaxed = true)
        val mockTask = mockk<Task<DocumentSnapshot>>(relaxed = true)
        val snapshot = mockk<DocumentSnapshot>()

        every { mockColl.document("user123") } returns userDoc
        every { userDoc.collection("resonance_out") } returns resonanceColl
        every { resonanceColl.document("target123") } returns targetDoc
        every { targetDoc.get() } returns mockTask
        coEvery { mockTask.await() } returns snapshot
        every { snapshot.exists() } returns true

        val res = repository.isResonating("user123", "target123")
        assertTrue(res)
    }
}