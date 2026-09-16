package com.saurabh.artifact.repository

import android.content.Context
import android.text.TextUtils
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
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
import com.saurabh.artifact.domain.auth.RegistrationResult
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.SigilConfig
import com.saurabh.artifact.model.User
import com.saurabh.artifact.worker.InteractionSyncWorker
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers {
            val arg = firstArg<CharSequence?>()
            arg == null || arg.isEmpty()
        }

        val mockSnapshot = mockk<DocumentSnapshot>(relaxed = true)
        
        coEvery { any<Task<DocumentSnapshot>>().await() } returns mockSnapshot
        coEvery { any<Task<QuerySnapshot>>().await() } returns mockk(relaxed = true)
        coEvery { any<Task<Void>>().await() } returns mockk(relaxed = true)
        coEvery { any<Task<Any>>().await() } returns mockk(relaxed = true)

        every { mockDoc.get() } returns mockk(relaxed = true)
        every { mockColl.document(any()) } returns mockDoc
        every { mockColl.get() } returns mockk(relaxed = true)
        
        every { firestore.collection(any()) } returns mockColl
        every { identityPolicy.isWithinWindow(any()) } returns false
        
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
    fun `getOrCreateProfile reconciles mismatched legacy identity seeds`() = runBlocking {
        val userId = "user123"
        val firebaseUser = mockk<FirebaseUser>()
        every { firebaseUser.uid } returns userId
        every { firebaseUser.email } returns "test@example.com"
        every { firebaseUser.displayName } returns "Test User"
        every { firebaseUser.reload() } returns mockk(relaxed = true)
        every { auth.currentUser } returns firebaseUser

        val snapshot = mockk<DocumentSnapshot>()
        val mismatchedUser = User(
            id = userId,
            anonymousName = "Valid Name",
            anonymousId = "usr_123",
            anonymousSigil = "23",
            sigilSeed = "seed_A",
            sigilConfig = SigilConfig(seed = "seed_B")
        )
        every { snapshot.exists() } returns true
        every { snapshot.toObject(User::class.java) } returns mismatchedUser
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
        assertNotNull(repairedUser)
        assertEquals("seed_B", repairedUser!!.sigilSeed)
        assertEquals("seed_B", repairedUser.sigilConfig.seed)

        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `updateSigilConfig writes matching sigilConfig seed and top-level sigilSeed`() = runBlocking {
        coEvery { regCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        val userId = "user123"
        val userRef = mockk<DocumentReference>(relaxed = true)
        every { mockColl.document(userId) } returns userRef

        val existingUser = User(id = userId, anonymousName = "Existing", sigilSeed = "old_seed", sigilConfig = SigilConfig(seed = "old_seed"))
        val snapshot = mockk<DocumentSnapshot>()
        every { snapshot.toObject(User::class.java) } returns existingUser
        every { snapshot.id } returns userId
        every { snapshot.exists() } returns true
        
        coEvery { any<Task<DocumentSnapshot>>().await() } returns snapshot

        val updateMapSlot = slot<Map<String, Any>>()
        val updateTask = mockk<Task<Void>>(relaxed = true)
        every { userRef.update(capture(updateMapSlot)) } returns updateTask
        coEvery { updateTask.await() } returns mockk(relaxed = true)

        val newConfig = SigilConfig(seed = "new_seed_123", version = 3)
        val result = repository.updateSigilConfig(userId, newConfig)

        assertTrue(result.isSuccess)
        assertEquals(newConfig, updateMapSlot.captured["sigilConfig"])
        assertEquals("new_seed_123", updateMapSlot.captured["sigilSeed"])

        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `existing artifact fallback rendering behavior remains intact`() {
        val artifactWithConfig = Artifact(
            id = "art_1",
            author = AuthorSnapshot(sigilSeed = "seed_snapshot", sigilConfig = SigilConfig(seed = "seed_config"))
        )
        assertEquals("seed_config", artifactWithConfig.authorSigilConfig.seed)

        val artifactFallbackToSigilSeed = Artifact(
            id = "art_2",
            author = AuthorSnapshot(sigilSeed = "seed_snapshot", sigilConfig = SigilConfig(seed = ""))
        )
        assertEquals("seed_snapshot", artifactFallbackToSigilSeed.authorSigilConfig.seed)

        val artifactFallbackToAnonymousId = Artifact(
            id = "art_3",
            author = AuthorSnapshot(anonymousId = "usr_99", sigilSeed = "", sigilConfig = SigilConfig(seed = ""))
        )
        assertEquals("usr_99", artifactFallbackToAnonymousId.authorSigilConfig.seed)

        val artifactFallbackToArtifactId = Artifact(
            id = "art_4",
            author = AuthorSnapshot(anonymousId = "", sigilSeed = "", sigilConfig = SigilConfig(seed = ""))
        )
        assertEquals("art_4", artifactFallbackToArtifactId.authorSigilConfig.seed)
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
    fun `getOrCreateProfile retries on PERMISSION_DENIED and succeeds without getIdToken(true)`() = runBlocking {
        val userId = "user123"
        val firebaseUser = mockk<FirebaseUser>()
        every { firebaseUser.uid } returns userId
        every { firebaseUser.email } returns "test@example.com"
        every { firebaseUser.displayName } returns "Test User"
        every { firebaseUser.reload() } returns mockk(relaxed = true)
        every { auth.currentUser } returns firebaseUser

        val snapshot = mockk<DocumentSnapshot>()
        val user = User(id = userId, anonymousName = "Name", anonymousId = "usr_123", anonymousSigil = "23", sigilSeed = "seed", sigilConfig = SigilConfig(seed = "seed", version = 3))
        every { snapshot.exists() } returns true
        every { snapshot.toObject(User::class.java) } returns user
        every { snapshot.id } returns userId
        every { snapshot.get(any<String>()) } returns null

        val transaction = mockk<Transaction>(relaxed = true)
        every { transaction.get(any<DocumentReference>()) } returns snapshot

        val permissionDenied = FirebaseFirestoreException("Permission denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        var callCount = 0

        every { firestore.runTransaction<Any>(any()) } answers {
            callCount++
            if (callCount == 1) {
                val failedTask = mockk<Task<Any>>()
                coEvery { failedTask.await() } throws permissionDenied
                failedTask
            } else {
                val block = firstArg<Transaction.Function<Any>>()
                val result = block.apply(transaction)
                val successTask = mockk<Task<Any>>(relaxed = true)
                coEvery { successTask.await() } returns (result ?: mockk())
                successTask
            }
        }

        val result = repository.getOrCreateProfile()

        assertTrue("Should succeed on retry", result.isSuccess)
        assertEquals(2, callCount)
        verify(exactly = 0) { firebaseUser.getIdToken(any()) }

        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `getOrCreateProfile fails after bounded retries on persistent PERMISSION_DENIED`() = runBlocking {
        val userId = "user123"
        val firebaseUser = mockk<FirebaseUser>()
        every { firebaseUser.uid } returns userId
        every { firebaseUser.email } returns "test@example.com"
        every { firebaseUser.displayName } returns "Test User"
        every { firebaseUser.reload() } returns mockk(relaxed = true)
        every { auth.currentUser } returns firebaseUser

        val permissionDenied = FirebaseFirestoreException("Permission denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)

        every { firestore.runTransaction<Any>(any()) } answers {
            val failedTask = mockk<Task<Any>>()
            coEvery { failedTask.await() } throws permissionDenied
            failedTask
        }

        val result = repository.getOrCreateProfile()

        assertTrue("Should fail on persistent permission denied", result.isFailure)
        verify(exactly = 5) { firestore.runTransaction<Any>(any()) }
        verify(exactly = 0) { firebaseUser.getIdToken(any()) }

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

    @Test
    fun `isUsernameAvailable normalizes username and checks document existence`() = runBlocking {
        val usernameDoc = mockk<DocumentReference>(relaxed = true)
        val snapshot = mockk<DocumentSnapshot>()
        val mockTask = mockk<Task<DocumentSnapshot>>(relaxed = true)

        every { mockColl.document("pro zach 627") } returns usernameDoc
        every { usernameDoc.get() } returns mockTask
        coEvery { mockTask.await() } returns snapshot
        every { snapshot.exists() } returns false

        val result = repository.isUsernameAvailable("  Pro Zach 627  ")

        assertTrue(result.isSuccess)
        assertEquals(true, result.getOrNull())
        verify(exactly = 1) { mockColl.document("pro zach 627") }
    }

    @Test
    fun `isUsernameAvailable returns false when username reservation exists`() = runBlocking {
        val usernameDoc = mockk<DocumentReference>(relaxed = true)
        val snapshot = mockk<DocumentSnapshot>()
        val mockTask = mockk<Task<DocumentSnapshot>>(relaxed = true)

        every { mockColl.document("takenuser") } returns usernameDoc
        every { usernameDoc.get() } returns mockTask
        coEvery { mockTask.await() } returns snapshot
        every { snapshot.exists() } returns true

        val result = repository.isUsernameAvailable("takenuser")

        assertTrue(result.isSuccess)
        assertEquals(false, result.getOrNull())
    }

    @Test
    fun `createUsername writes privacy-safe schema without uid or userId fields`() = runBlocking {
        every { auth.currentUser } returns mockk(relaxed = true)
        coEvery { regCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser

        val userId = "user123"
        val userRef = mockk<DocumentReference>(relaxed = true)
        val userSnap = mockk<DocumentSnapshot>()
        val existingUser = User(id = userId, anonymousName = "Old Name")
        every { mockColl.document(userId) } returns userRef
        every { userSnap.exists() } returns true
        every { userSnap.toObject(User::class.java) } returns existingUser
        every { userSnap.id } returns userId
        
        val userGetTask = mockk<Task<DocumentSnapshot>>(relaxed = true)
        every { userRef.get() } returns userGetTask
        coEvery { userGetTask.await() } returns userSnap

        val usernameRef = mockk<DocumentReference>(relaxed = true)
        every { mockColl.document("newname") } returns usernameRef

        val usernameSnap = mockk<DocumentSnapshot>()
        every { usernameSnap.exists() } returns false

        val oldUsernameRef = mockk<DocumentReference>(relaxed = true)
        every { mockColl.document("old name") } returns oldUsernameRef

        val userDocInTx = mockk<DocumentSnapshot>()
        every { userDocInTx.getString("anonymousName") } returns "Old Name"

        val oldUsernameDocInTx = mockk<DocumentSnapshot>()
        every { oldUsernameDocInTx.exists() } returns true
        val createdAtTs = mockk<Timestamp>()
        every { oldUsernameDocInTx.get("createdAt") } returns createdAtTs

        val transaction = mockk<Transaction>(relaxed = true)
        every { transaction.get(usernameRef) } returns usernameSnap
        every { transaction.get(userRef) } returns userDocInTx
        every { transaction.get(oldUsernameRef) } returns oldUsernameDocInTx

        val setMapSlot = slot<Map<String, Any>>()
        every { transaction.set(eq(usernameRef), capture(setMapSlot)) } returns transaction

        val retiredSetMapSlot = slot<Map<String, Any>>()
        every { transaction.set(eq(oldUsernameRef), capture(retiredSetMapSlot)) } returns transaction

        every { firestore.runTransaction<Unit>(any()) } answers {
            val block = firstArg<Transaction.Function<Unit>>()
            block.apply(transaction)
            val task = mockk<Task<Unit>>(relaxed = true)
            coEvery { task.await() } returns Unit
            task
        }

        val result = repository.createUsername(userId, "NewName")

        assertTrue(result.isSuccess)
        val mapWritten = setMapSlot.captured
        assertEquals(true, mapWritten["reserved"])
        assertEquals("ACTIVE", mapWritten["status"])
        assertFalse("Written schema must NOT contain uid", mapWritten.containsKey("uid"))
        assertFalse("Written schema must NOT contain userId", mapWritten.containsKey("userId"))

        val retiredMapWritten = retiredSetMapSlot.captured
        assertEquals(true, retiredMapWritten["reserved"])
        assertEquals("RETIRED", retiredMapWritten["status"])
        assertEquals(createdAtTs, retiredMapWritten["createdAt"])
        assertTrue(retiredMapWritten.containsKey("retiredAt"))

        verify(exactly = 0) { transaction.delete(any()) }
    }

    @Test
    fun `getOrCreateProfile lastSeen migration succeeds`() = runBlocking {
        val userId = "user123"
        val firebaseUser = mockk<FirebaseUser>()
        every { firebaseUser.uid } returns userId
        every { firebaseUser.email } returns "test@example.com"
        every { firebaseUser.displayName } returns "Test User"
        every { firebaseUser.reload() } returns mockk(relaxed = true)
        every { auth.currentUser } returns firebaseUser

        val snapshot = mockk<DocumentSnapshot>()
        val user = User(id = userId, anonymousName = "Name", anonymousId = "usr_123", anonymousSigil = "23", sigilSeed = "seed", sigilConfig = SigilConfig(seed = "seed", version = 3))
        every { snapshot.exists() } returns true
        every { snapshot.toObject(User::class.java) } returns user
        every { snapshot.id } returns userId
        
        val rootData = mapOf("lastSeen" to 123456789L, "email" to "test@example.com")
        every { snapshot.get(any<String>()) } answers {
            val key = firstArg<String>()
            rootData[key]
        }

        val privateSnapshot = mockk<DocumentSnapshot>()
        every { privateSnapshot.exists() } returns true

        val transaction = mockk<Transaction>(relaxed = true)
        val fieldsMovedSlot = slot<Map<String, Any>>()
        val deletionsSlot = slot<Map<String, Any>>()

        every { transaction.get(any<DocumentReference>()) } answers {
            val ref = firstArg<DocumentReference>()
            if (ref.path.contains("private")) privateSnapshot else snapshot
        }
        every { transaction.set(any(), capture(fieldsMovedSlot), any()) } returns transaction
        every { transaction.update(any<DocumentReference>(), capture(deletionsSlot)) } returns transaction

        every { firestore.runTransaction<Any>(any()) } answers {
            val block = firstArg<Transaction.Function<Any>>()
            val result = block.apply(transaction)
            val successTask = mockk<Task<Any>>(relaxed = true)
            coEvery { successTask.await() } returns (result ?: mockk())
            successTask
        }

        val result = repository.getOrCreateProfile()

        assertTrue(result.isSuccess)
        assertEquals(123456789L, fieldsMovedSlot.captured["lastSeen"])
        assertEquals("test@example.com", fieldsMovedSlot.captured["email"])
        assertFalse(fieldsMovedSlot.captured.containsKey("isAdmin"))
        assertFalse(fieldsMovedSlot.captured.containsKey("accountStatus"))
        assertFalse(fieldsMovedSlot.captured.containsKey("admin"))
        assertTrue(deletionsSlot.captured.containsKey("lastSeen"))
        assertTrue(deletionsSlot.captured.containsKey("email"))

        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `getOrCreateProfile excludes protected fields from fieldsToMove and preserves them on userRef`() = runBlocking {
        val userId = "user123"
        val firebaseUser = mockk<FirebaseUser>()
        every { firebaseUser.uid } returns userId
        every { firebaseUser.email } returns "test@example.com"
        every { firebaseUser.displayName } returns "Test User"
        every { firebaseUser.reload() } returns mockk(relaxed = true)
        every { auth.currentUser } returns firebaseUser

        val snapshot = mockk<DocumentSnapshot>()
        val user = User(id = userId, anonymousName = "Name", anonymousId = "usr_123", anonymousSigil = "23", sigilSeed = "seed", sigilConfig = SigilConfig(seed = "seed", version = 3))
        every { snapshot.exists() } returns true
        every { snapshot.toObject(User::class.java) } returns user
        every { snapshot.id } returns userId

        val rootData = mapOf("isAdmin" to true, "accountStatus" to "ACTIVE", "admin" to true, "lastSeen" to 999999L)
        every { snapshot.get(any<String>()) } answers {
            val key = firstArg<String>()
            rootData[key]
        }

        val privateSnapshot = mockk<DocumentSnapshot>()
        every { privateSnapshot.exists() } returns true

        val transaction = mockk<Transaction>(relaxed = true)
        val fieldsMovedSlot = slot<Map<String, Any>>()
        val deletionsSlot = slot<Map<String, Any>>()

        every { transaction.get(any<DocumentReference>()) } answers {
            val ref = firstArg<DocumentReference>()
            if (ref.path.contains("private")) privateSnapshot else snapshot
        }
        every { transaction.set(any(), capture(fieldsMovedSlot), any()) } returns transaction
        every { transaction.update(any<DocumentReference>(), capture(deletionsSlot)) } returns transaction

        every { firestore.runTransaction<Any>(any()) } answers {
            val block = firstArg<Transaction.Function<Any>>()
            val result = block.apply(transaction)
            val successTask = mockk<Task<Any>>(relaxed = true)
            coEvery { successTask.await() } returns (result ?: mockk())
            successTask
        }

        val result = repository.getOrCreateProfile()

        assertTrue(result.isSuccess)
        val fieldsToMove = fieldsMovedSlot.captured
        assertEquals(999999L, fieldsToMove["lastSeen"])
        assertFalse("isAdmin must not be in fieldsToMove", fieldsToMove.containsKey("isAdmin"))
        assertFalse("accountStatus must not be in fieldsToMove", fieldsToMove.containsKey("accountStatus"))
        assertFalse("admin must not be in fieldsToMove", fieldsToMove.containsKey("admin"))

        val deletions = deletionsSlot.captured
        assertTrue("lastSeen should be deleted from root doc", deletions.containsKey("lastSeen"))
        assertFalse("isAdmin must NOT be deleted from root doc", deletions.containsKey("isAdmin"))
        assertFalse("accountStatus must NOT be deleted from root doc", deletions.containsKey("accountStatus"))
        assertFalse("admin must NOT be deleted from root doc", deletions.containsKey("admin"))

        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `getOrCreateProfile does not attempt private settings updates when document only contains protected fields`() = runBlocking {
        val userId = "user123"
        val firebaseUser = mockk<FirebaseUser>()
        every { firebaseUser.uid } returns userId
        every { firebaseUser.email } returns "test@example.com"
        every { firebaseUser.displayName } returns "Test User"
        every { firebaseUser.reload() } returns mockk(relaxed = true)
        every { auth.currentUser } returns firebaseUser

        val snapshot = mockk<DocumentSnapshot>()
        val user = User(id = userId, anonymousName = "Name", anonymousId = "usr_123", anonymousSigil = "23", sigilSeed = "seed", sigilConfig = SigilConfig(seed = "seed", version = 3))
        every { snapshot.exists() } returns true
        every { snapshot.toObject(User::class.java) } returns user
        every { snapshot.id } returns userId

        val rootData = mapOf("isAdmin" to true, "accountStatus" to "ACTIVE", "admin" to true)
        every { snapshot.get(any<String>()) } answers {
            val key = firstArg<String>()
            rootData[key]
        }

        val privateSnapshot = mockk<DocumentSnapshot>()
        every { privateSnapshot.exists() } returns true

        val transaction = mockk<Transaction>(relaxed = true)

        every { transaction.get(any<DocumentReference>()) } answers {
            val ref = firstArg<DocumentReference>()
            if (ref.path.contains("private")) privateSnapshot else snapshot
        }

        every { firestore.runTransaction<Any>(any()) } answers {
            val block = firstArg<Transaction.Function<Any>>()
            val result = block.apply(transaction)
            val successTask = mockk<Task<Any>>(relaxed = true)
            coEvery { successTask.await() } returns (result ?: mockk())
            successTask
        }

        val result = repository.getOrCreateProfile()

        assertTrue(result.isSuccess)
        verify(exactly = 0) { transaction.set(any(), any(), any()) }
        verify(exactly = 0) { transaction.update(any<DocumentReference>(), any()) }

        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `new profile creates an ACTIVE username reservation`() = runBlocking {
        val userId = "user_new_123"
        val firebaseUser = mockk<FirebaseUser>()
        every { firebaseUser.uid } returns userId
        every { firebaseUser.email } returns "new@example.com"
        every { firebaseUser.displayName } returns "New User"
        every { firebaseUser.reload() } returns mockk(relaxed = true)
        every { auth.currentUser } returns firebaseUser

        val userRef = mockk<DocumentReference>(relaxed = true)
        val privateRef = mockk<DocumentReference>(relaxed = true)
        every { mockColl.document(userId) } returns userRef
        every { userRef.collection("private").document("settings") } returns privateRef

        val snapshot = mockk<DocumentSnapshot>()
        every { snapshot.exists() } returns false

        val usernameSnap = mockk<DocumentSnapshot>()
        every { usernameSnap.exists() } returns false

        val transaction = mockk<Transaction>(relaxed = true)
        every { transaction.get(userRef) } returns snapshot
        every { transaction.get(any<DocumentReference>()) } returns usernameSnap

        val setMapSlots = mutableListOf<Map<String, Any>>()
        val setDocRefs = mutableListOf<DocumentReference>()
        every { transaction.set(capture(setDocRefs), capture(setMapSlots)) } returns transaction

        every { firestore.runTransaction<Any>(any()) } answers {
            val block = firstArg<Transaction.Function<Any>>()
            val result = block.apply(transaction)
            val task = mockk<Task<Any>>(relaxed = true)
            coEvery { task.await() } returns (result ?: mockk())
            task
        }

        val result = repository.getOrCreateProfile()

        assertTrue(result.isSuccess)
        assertTrue("isNewUser should be true", result.getOrNull()?.isNewUser == true)

        val usernameReservationIndex = setMapSlots.indexOfFirst { it["status"] == "ACTIVE" && it["reserved"] == true }
        assertTrue("ACTIVE username reservation map should be written", usernameReservationIndex >= 0)
        val reservationMap = setMapSlots[usernameReservationIndex]
        assertEquals(true, reservationMap["reserved"])
        assertEquals("ACTIVE", reservationMap["status"])
        assertTrue("createdAt timestamp should be set", reservationMap.containsKey("createdAt"))

        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `existing profile missing a reservation receives a lazy ACTIVE reservation`() = runBlocking {
        val userId = "user_existing_123"
        val firebaseUser = mockk<FirebaseUser>()
        every { firebaseUser.uid } returns userId
        every { firebaseUser.email } returns "existing@example.com"
        every { firebaseUser.displayName } returns "Existing User"
        every { firebaseUser.reload() } returns mockk(relaxed = true)
        every { auth.currentUser } returns firebaseUser

        val userRef = mockk<DocumentReference>(relaxed = true)
        val privateRef = mockk<DocumentReference>(relaxed = true)
        val usernameRef = mockk<DocumentReference>(relaxed = true)

        every { mockColl.document(userId) } returns userRef
        every { userRef.collection("private").document("settings") } returns privateRef
        every { mockColl.document("existing_name") } returns usernameRef

        val snapshot = mockk<DocumentSnapshot>()
        val existingUser = User(id = userId, anonymousName = "existing_name", anonymousId = "usr_123", anonymousSigil = "23", sigilSeed = "seed", sigilConfig = SigilConfig(seed = "seed", version = 3))
        every { snapshot.exists() } returns true
        every { snapshot.toObject(User::class.java) } returns existingUser
        every { snapshot.id } returns userId
        every { snapshot.get(any<String>()) } returns null

        val privateSnapshot = mockk<DocumentSnapshot>()
        every { privateSnapshot.exists() } returns true

        val usernameSnap = mockk<DocumentSnapshot>()
        every { usernameSnap.exists() } returns false // Missing reservation document!

        val transaction = mockk<Transaction>(relaxed = true)
        every { transaction.get(userRef) } returns snapshot
        every { transaction.get(privateRef) } returns privateSnapshot
        every { transaction.get(usernameRef) } returns usernameSnap

        val setMapSlots = mutableListOf<Map<String, Any>>()
        val setDocRefs = mutableListOf<DocumentReference>()
        every { transaction.set(capture(setDocRefs), capture(setMapSlots)) } returns transaction

        every { firestore.runTransaction<Any>(any()) } answers {
            val block = firstArg<Transaction.Function<Any>>()
            val result = block.apply(transaction)
            val task = mockk<Task<Any>>(relaxed = true)
            coEvery { task.await() } returns (result ?: mockk())
            task
        }

        val result = repository.getOrCreateProfile()

        assertTrue(result.isSuccess)
        val lazyReservationIndex = setMapSlots.indexOfFirst { it["status"] == "ACTIVE" && it["reserved"] == true }
        assertTrue("Lazy ACTIVE reservation should be created", lazyReservationIndex >= 0)
        val reservationMap = setMapSlots[lazyReservationIndex]
        assertEquals(true, reservationMap["reserved"])
        assertEquals("ACTIVE", reservationMap["status"])
        assertTrue(reservationMap.containsKey("createdAt"))

        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `normal identity change retires old ACTIVE reservation and creates new ACTIVE reservation`() = runBlocking {
        every { auth.currentUser } returns mockk(relaxed = true)
        coEvery { regCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser

        val userId = "user123"
        val userRef = mockk<DocumentReference>(relaxed = true)
        val userSnap = mockk<DocumentSnapshot>()
        val existingUser = User(id = userId, anonymousName = "Old Name")
        every { mockColl.document(userId) } returns userRef
        every { userSnap.exists() } returns true
        every { userSnap.toObject(User::class.java) } returns existingUser
        every { userSnap.id } returns userId

        val userGetTask = mockk<Task<DocumentSnapshot>>(relaxed = true)
        every { userRef.get() } returns userGetTask
        coEvery { userGetTask.await() } returns userSnap

        val newUsernameRef = mockk<DocumentReference>(relaxed = true)
        every { mockColl.document("newname") } returns newUsernameRef

        val newUsernameSnap = mockk<DocumentSnapshot>()
        every { newUsernameSnap.exists() } returns false

        val oldUsernameRef = mockk<DocumentReference>(relaxed = true)
        every { mockColl.document("old name") } returns oldUsernameRef

        val userDocInTx = mockk<DocumentSnapshot>()
        every { userDocInTx.getString("anonymousName") } returns "Old Name"

        val oldUsernameDocInTx = mockk<DocumentSnapshot>()
        every { oldUsernameDocInTx.exists() } returns true
        val createdAtTs = mockk<Timestamp>()
        every { oldUsernameDocInTx.get("createdAt") } returns createdAtTs

        val transaction = mockk<Transaction>(relaxed = true)
        every { transaction.get(newUsernameRef) } returns newUsernameSnap
        every { transaction.get(userRef) } returns userDocInTx
        every { transaction.get(oldUsernameRef) } returns oldUsernameDocInTx

        val newSetMapSlot = slot<Map<String, Any>>()
        every { transaction.set(eq(newUsernameRef), capture(newSetMapSlot)) } returns transaction

        val oldSetMapSlot = slot<Map<String, Any>>()
        every { transaction.set(eq(oldUsernameRef), capture(oldSetMapSlot)) } returns transaction

        every { firestore.runTransaction<Unit>(any()) } answers {
            val block = firstArg<Transaction.Function<Unit>>()
            block.apply(transaction)
            val task = mockk<Task<Unit>>(relaxed = true)
            coEvery { task.await() } returns Unit
            task
        }

        val result = repository.createUsername(userId, "NewName")

        assertTrue(result.isSuccess)
        assertEquals("ACTIVE", newSetMapSlot.captured["status"])
        assertEquals(true, newSetMapSlot.captured["reserved"])

        assertEquals("RETIRED", oldSetMapSlot.captured["status"])
        assertEquals(true, oldSetMapSlot.captured["reserved"])
        assertEquals(createdAtTs, oldSetMapSlot.captured["createdAt"])
    }

    @Test
    fun `identity change with missing legacy old reservation succeeds without PERMISSION_DENIED`() = runBlocking {
        every { auth.currentUser } returns mockk(relaxed = true)
        coEvery { regCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser

        val userId = "user123"
        val userRef = mockk<DocumentReference>(relaxed = true)
        val userSnap = mockk<DocumentSnapshot>()
        val existingUser = User(id = userId, anonymousName = "Legacy Old Name")
        every { mockColl.document(userId) } returns userRef
        every { userSnap.exists() } returns true
        every { userSnap.toObject(User::class.java) } returns existingUser
        every { userSnap.id } returns userId

        val userGetTask = mockk<Task<DocumentSnapshot>>(relaxed = true)
        every { userRef.get() } returns userGetTask
        coEvery { userGetTask.await() } returns userSnap

        val newUsernameRef = mockk<DocumentReference>(relaxed = true)
        every { mockColl.document("newname") } returns newUsernameRef

        val newUsernameSnap = mockk<DocumentSnapshot>()
        every { newUsernameSnap.exists() } returns false

        val oldUsernameRef = mockk<DocumentReference>(relaxed = true)
        every { mockColl.document("legacy old name") } returns oldUsernameRef

        val userDocInTx = mockk<DocumentSnapshot>()
        every { userDocInTx.getString("anonymousName") } returns "Legacy Old Name"

        val oldUsernameDocInTx = mockk<DocumentSnapshot>()
        every { oldUsernameDocInTx.exists() } returns false

        val transaction = mockk<Transaction>(relaxed = true)
        every { transaction.get(newUsernameRef) } returns newUsernameSnap
        every { transaction.get(userRef) } returns userDocInTx
        every { transaction.get(oldUsernameRef) } returns oldUsernameDocInTx

        val newSetMapSlot = slot<Map<String, Any>>()
        every { transaction.set(eq(newUsernameRef), capture(newSetMapSlot)) } returns transaction

        every { firestore.runTransaction<Unit>(any()) } answers {
            val block = firstArg<Transaction.Function<Unit>>()
            block.apply(transaction)
            val task = mockk<Task<Unit>>(relaxed = true)
            coEvery { task.await() } returns Unit
            task
        }

        val result = repository.createUsername(userId, "NewName")

        assertTrue("Update must succeed even when old username reservation doc is missing", result.isSuccess)
        assertEquals("ACTIVE", newSetMapSlot.captured["status"])
        verify(exactly = 0) { transaction.set(eq(oldUsernameRef), any()) }
    }

    @Test
    fun `createUsername with leading and trailing whitespace stores trimmed anonymousName`() = runBlocking {
        every { auth.currentUser } returns mockk(relaxed = true)
        coEvery { regCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser

        val userId = "user123"
        val userRef = mockk<DocumentReference>(relaxed = true)
        val userSnap = mockk<DocumentSnapshot>()
        val existingUser = User(id = userId, anonymousName = "OldName")
        every { mockColl.document(userId) } returns userRef
        every { userSnap.exists() } returns true
        every { userSnap.toObject(User::class.java) } returns existingUser
        every { userSnap.id } returns userId

        val userGetTask = mockk<Task<DocumentSnapshot>>(relaxed = true)
        every { userRef.get() } returns userGetTask
        coEvery { userGetTask.await() } returns userSnap

        val newUsernameRef = mockk<DocumentReference>(relaxed = true)
        every { mockColl.document("paddedname") } returns newUsernameRef

        val newUsernameSnap = mockk<DocumentSnapshot>()
        every { newUsernameSnap.exists() } returns false

        val oldUsernameRef = mockk<DocumentReference>(relaxed = true)
        every { mockColl.document("oldname") } returns oldUsernameRef

        val userDocInTx = mockk<DocumentSnapshot>()
        every { userDocInTx.getString("anonymousName") } returns "OldName"

        val oldUsernameDocInTx = mockk<DocumentSnapshot>()
        every { oldUsernameDocInTx.exists() } returns true
        every { oldUsernameDocInTx.get("createdAt") } returns null

        val transaction = mockk<Transaction>(relaxed = true)
        every { transaction.get(newUsernameRef) } returns newUsernameSnap
        every { transaction.get(userRef) } returns userDocInTx
        every { transaction.get(oldUsernameRef) } returns oldUsernameDocInTx

        val newSetMapSlot = slot<Map<String, Any>>()
        every { transaction.set(eq(newUsernameRef), capture(newSetMapSlot)) } returns transaction

        val oldSetMapSlot = slot<Map<String, Any>>()
        every { transaction.set(eq(oldUsernameRef), capture(oldSetMapSlot)) } returns transaction

        val userUpdateSlot = slot<Map<String, Any>>()
        every { transaction.update(eq(userRef), capture(userUpdateSlot)) } returns transaction

        every { firestore.runTransaction<Unit>(any()) } answers {
            val block = firstArg<Transaction.Function<Unit>>()
            block.apply(transaction)
            val task = mockk<Task<Unit>>(relaxed = true)
            coEvery { task.await() } returns Unit
            task
        }

        val result = repository.createUsername(userId, "  PaddedName  ")

        assertTrue(result.isSuccess)
        assertEquals("PaddedName", userUpdateSlot.captured["anonymousName"])
    }

    @Test
    fun `legacy padded Jonathan profile is repaired before identity retirement and Jonathan reservation is retired`() = runBlocking {
        every { auth.currentUser } returns mockk(relaxed = true)
        coEvery { regCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser

        val userId = "user_jonathan"
        val userRef = mockk<DocumentReference>(relaxed = true)
        val userSnap = mockk<DocumentSnapshot>()
        val legacyUser = User(id = userId, anonymousName = "Jonathan ")
        every { mockColl.document(userId) } returns userRef
        every { userSnap.exists() } returns true
        every { userSnap.toObject(User::class.java) } returns legacyUser
        every { userSnap.id } returns userId

        val userGetTask = mockk<Task<DocumentSnapshot>>(relaxed = true)
        every { userRef.get() } returns userGetTask
        coEvery { userGetTask.await() } returns userSnap

        val repairUpdateTask = mockk<Task<Void>>(relaxed = true)
        every { userRef.update("anonymousName", "Jonathan") } returns repairUpdateTask
        coEvery { repairUpdateTask.await() } returns mockk(relaxed = true)

        val newUsernameRef = mockk<DocumentReference>(relaxed = true)
        every { mockColl.document("newidentity") } returns newUsernameRef

        val newUsernameSnap = mockk<DocumentSnapshot>()
        every { newUsernameSnap.exists() } returns false

        val oldUsernameRef = mockk<DocumentReference>(relaxed = true)
        every { mockColl.document("jonathan") } returns oldUsernameRef

        val userDocInTx = mockk<DocumentSnapshot>()
        every { userDocInTx.getString("anonymousName") } returns "Jonathan"

        val oldUsernameDocInTx = mockk<DocumentSnapshot>()
        every { oldUsernameDocInTx.exists() } returns true
        val createdAtTs = mockk<Timestamp>()
        every { oldUsernameDocInTx.get("createdAt") } returns createdAtTs

        val transaction = mockk<Transaction>(relaxed = true)
        every { transaction.get(newUsernameRef) } returns newUsernameSnap
        every { transaction.get(userRef) } returns userDocInTx
        every { transaction.get(oldUsernameRef) } returns oldUsernameDocInTx

        val newSetMapSlot = slot<Map<String, Any>>()
        every { transaction.set(eq(newUsernameRef), capture(newSetMapSlot)) } returns transaction

        val oldSetMapSlot = slot<Map<String, Any>>()
        every { transaction.set(eq(oldUsernameRef), capture(oldSetMapSlot)) } returns transaction

        every { firestore.runTransaction<Unit>(any()) } answers {
            val block = firstArg<Transaction.Function<Unit>>()
            block.apply(transaction)
            val task = mockk<Task<Unit>>(relaxed = true)
            coEvery { task.await() } returns Unit
            task
        }

        val result = repository.createUsername(userId, "NewIdentity")

        assertTrue(result.isSuccess)
        verify { userRef.update("anonymousName", "Jonathan") }
        assertEquals("RETIRED", oldSetMapSlot.captured["status"])
        assertEquals(true, oldSetMapSlot.captured["reserved"])
        assertEquals("ACTIVE", newSetMapSlot.captured["status"])
    }

    @Test
    fun `legacy reservation without status field can still be retired`() = runBlocking {
        every { auth.currentUser } returns mockk(relaxed = true)
        coEvery { regCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser

        val userId = "user123"
        val userRef = mockk<DocumentReference>(relaxed = true)
        val userSnap = mockk<DocumentSnapshot>()
        val existingUser = User(id = userId, anonymousName = "LegacyName")
        every { mockColl.document(userId) } returns userRef
        every { userSnap.exists() } returns true
        every { userSnap.toObject(User::class.java) } returns existingUser
        every { userSnap.id } returns userId

        val userGetTask = mockk<Task<DocumentSnapshot>>(relaxed = true)
        every { userRef.get() } returns userGetTask
        coEvery { userGetTask.await() } returns userSnap

        val newUsernameRef = mockk<DocumentReference>(relaxed = true)
        every { mockColl.document("nextname") } returns newUsernameRef

        val newUsernameSnap = mockk<DocumentSnapshot>()
        every { newUsernameSnap.exists() } returns false

        val oldUsernameRef = mockk<DocumentReference>(relaxed = true)
        every { mockColl.document("legacyname") } returns oldUsernameRef

        val userDocInTx = mockk<DocumentSnapshot>()
        every { userDocInTx.getString("anonymousName") } returns "LegacyName"

        val oldUsernameDocInTx = mockk<DocumentSnapshot>()
        every { oldUsernameDocInTx.exists() } returns true
        every { oldUsernameDocInTx.getString("status") } returns null
        every { oldUsernameDocInTx.get("createdAt") } returns null

        val transaction = mockk<Transaction>(relaxed = true)
        every { transaction.get(newUsernameRef) } returns newUsernameSnap
        every { transaction.get(userRef) } returns userDocInTx
        every { transaction.get(oldUsernameRef) } returns oldUsernameDocInTx

        val newSetMapSlot = slot<Map<String, Any>>()
        every { transaction.set(eq(newUsernameRef), capture(newSetMapSlot)) } returns transaction

        val oldSetMapSlot = slot<Map<String, Any>>()
        every { transaction.set(eq(oldUsernameRef), capture(oldSetMapSlot)) } returns transaction

        every { firestore.runTransaction<Unit>(any()) } answers {
            val block = firstArg<Transaction.Function<Unit>>()
            block.apply(transaction)
            val task = mockk<Task<Unit>>(relaxed = true)
            coEvery { task.await() } returns Unit
            task
        }

        val result = repository.createUsername(userId, "NextName")

        assertTrue(result.isSuccess)
        assertEquals("RETIRED", oldSetMapSlot.captured["status"])
        assertEquals(true, oldSetMapSlot.captured["reserved"])
    }

    @Test
    fun `permanent RETIRED identities remain unavailable`() = runBlocking {
        every { auth.currentUser } returns mockk(relaxed = true)
        coEvery { regCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser

        val usernameRef = mockk<DocumentReference>(relaxed = true)
        val usernameSnap = mockk<DocumentSnapshot>()
        every { usernameSnap.exists() } returns true
        every { usernameSnap.getString("status") } returns "RETIRED"
        
        val getTask = mockk<Task<DocumentSnapshot>>(relaxed = true)
        every { usernameRef.get() } returns getTask
        coEvery { getTask.await() } returns usernameSnap
        every { mockColl.document("retiredname") } returns usernameRef

        val availResult = repository.isUsernameAvailable("retiredname")
        assertTrue(availResult.isSuccess)
        assertFalse("Retired username must NOT be available", availResult.getOrThrow())
    }

    @Test
    fun `repair failure prevents identity transaction from executing`() = runBlocking {
        every { auth.currentUser } returns mockk(relaxed = true)
        coEvery { regCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser

        val userId = "user123"
        val userRef = mockk<DocumentReference>(relaxed = true)
        val userSnap = mockk<DocumentSnapshot>()
        val legacyUser = User(id = userId, anonymousName = "Jonathan ")
        every { mockColl.document(userId) } returns userRef
        every { userSnap.exists() } returns true
        every { userSnap.toObject(User::class.java) } returns legacyUser
        every { userSnap.id } returns userId

        val userGetTask = mockk<Task<DocumentSnapshot>>(relaxed = true)
        every { userRef.get() } returns userGetTask
        coEvery { userGetTask.await() } returns userSnap

        val repairUpdateTask = mockk<Task<Void>>(relaxed = true)
        every { userRef.update("anonymousName", "Jonathan") } returns repairUpdateTask
        coEvery { repairUpdateTask.await() } throws FirebaseFirestoreException("Permission denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)

        val result = repository.createUsername(userId, "NewName")

        assertTrue(result.isFailure)
        verify(exactly = 0) { firestore.runTransaction<Unit>(any()) }
    }
}

