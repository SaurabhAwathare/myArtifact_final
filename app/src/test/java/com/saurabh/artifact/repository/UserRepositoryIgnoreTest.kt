package com.saurabh.artifact.repository

import android.content.Context
import androidx.room.withTransaction
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.IgnoredUserDao
import com.saurabh.artifact.data.local.IgnoredUserEntity
import com.saurabh.artifact.data.local.InteractionAction
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.data.local.PendingInteractionEntity
import com.saurabh.artifact.data.local.UserDao
import com.saurabh.artifact.data.local.UserLocalEntity
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.domain.IdentityProtectionPolicy
import com.saurabh.artifact.domain.auth.RegistrationCoordinator
import com.saurabh.artifact.worker.InteractionSyncWorker
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UserRepositoryIgnoreTest {
    private val context = mockk<Context>(relaxed = true)
    private val auth = mockk<FirebaseAuth>()
    private val firestore = mockk<FirebaseFirestore>()
    private val userDao = mockk<UserDao>(relaxed = true)
    private val ignoredUserDao = mockk<IgnoredUserDao>(relaxed = true)
    private val pendingInteractionDao = mockk<PendingInteractionDao>(relaxed = true)
    private val appDatabase = mockk<AppDatabase>(relaxed = true)
    private val identityPolicy = mockk<IdentityProtectionPolicy>()
    private val regCoordinator = mockk<RegistrationCoordinator>()
    private val logger = mockk<DiagnosticLogger>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)

    private val mockDoc = mockk<DocumentReference>(relaxed = true)
    private val mockColl = mockk<CollectionReference>(relaxed = true)
    private val mockTask = mockk<Task<Void>>(relaxed = true)

    private lateinit var repository: UserRepository

    @Before
    fun setup() {
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        mockkStatic("androidx.room.RoomDatabaseKt")
        mockkObject(InteractionSyncWorker.Companion)
        every { InteractionSyncWorker.enqueue(any()) } just runs

        val firebaseUser = mockk<FirebaseUser>()
        every { firebaseUser.uid } returns "userA"
        every { auth.currentUser } returns firebaseUser
        every { authRepository.currentUserId } returns "userA"

        // Mock database.withTransaction to execute block directly
        val lambdaSlot = slot<suspend () -> Any?>()
        coEvery { appDatabase.withTransaction(capture(lambdaSlot)) } coAnswers {
            lambdaSlot.captured.invoke()
        }

        // Mock TasksKt await
        coEvery { any<Task<*>>().await() } returns mockk()

        every { mockDoc.set(any()) } returns mockTask
        every { mockDoc.delete() } returns mockTask
        every { mockDoc.collection(any()) } returns mockColl
        every { mockColl.document(any()) } returns mockDoc
        every { firestore.collection(any()) } returns mockColl

        // Provide default profile for current user to avoid self-ignore lookup hanging
        val defaultSelfProfile = UserLocalEntity(
            id = "userA",
            anonymousId = "usr_userA",
            anonymousName = "User A",
            anonymousSigil = "sigil",
            sigilSeed = "seed",
            sigilColor = "#FFD700",
            sigilConfigJson = "{}"
        )
        coEvery { userDao.getProfile("userA") } returns defaultSelfProfile

        repository = UserRepository(
            context = context,
            auth = auth,
            firestore = firestore,
            userDao = Lazy { userDao },
            identityProtectionPolicy = identityPolicy,
            registrationCoordinator = Lazy { regCoordinator },
            pendingInteractionDao = Lazy { pendingInteractionDao },
            ignoredUserDao = Lazy { ignoredUserDao },
            diagnosticLogger = logger,
            functions = Lazy { mockk(relaxed = true) },
            authRepository = Lazy { authRepository },
            appDatabase = Lazy { appDatabase }
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
        unmockkStatic("androidx.room.RoomDatabaseKt")
        unmockkObject(InteractionSyncWorker.Companion)
    }

    @Test
    fun `ignoreUser with canonical persona ID persists to Room atomically and enqueues worker`() = runTest {
        val canonicalPersonaId = "usr_target_123"

        val result = repository.ignoreUser(canonicalPersonaId)

        assertTrue(result.isSuccess)
        coVerify { appDatabase.withTransaction(any()) }
        coVerify { ignoredUserDao.insert(match { it.ownerUserId == "userA" && it.userId == canonicalPersonaId }) }
        coVerify {
            pendingInteractionDao.replacePendingInteraction(match {
                it.userId == "userA" &&
                it.artifactId == canonicalPersonaId &&
                it.interactionType == InteractionType.IGNORE &&
                it.action == InteractionAction.ADD &&
                it.metadata == canonicalPersonaId
            })
        }
        verify(exactly = 1) { InteractionSyncWorker.enqueue(context) }
        verify(exactly = 0) { mockDoc.set(any()) } // Direct Firestore write is deferred to worker
    }

    @Test
    fun `ignoreUser with resolvable UID resolves canonical persona ID and succeeds`() = runTest {
        val nonPersonaId = "raw_uid_999"
        val resolvedPersonaId = "usr_resolved_999"

        val mockUserLocal = UserLocalEntity(
            id = nonPersonaId,
            anonymousId = resolvedPersonaId,
            anonymousName = "Quiet Persona",
            anonymousSigil = "sigil",
            sigilSeed = "seed",
            sigilColor = "#FFD700",
            sigilConfigJson = "{}"
        )
        coEvery { userDao.getProfile(nonPersonaId) } returns mockUserLocal

        val result = repository.ignoreUser(nonPersonaId)

        assertTrue(result.isSuccess)
        coVerify { ignoredUserDao.insert(match { it.ownerUserId == "userA" && it.userId == resolvedPersonaId }) }
        coVerify {
            pendingInteractionDao.replacePendingInteraction(match {
                it.artifactId == resolvedPersonaId &&
                it.interactionType == InteractionType.IGNORE &&
                it.action == InteractionAction.ADD
            })
        }
    }

    @Test
    fun `ignoreUser with unresolvable target fails safely without modifying Room`() = runTest {
        val unresolvableId = "invalid_user"
        coEvery { userDao.getProfile(unresolvableId) } returns null
        val emptyDoc = mockk<DocumentSnapshot>()
        every { emptyDoc.exists() } returns false
        val emptyDocTask = mockk<Task<DocumentSnapshot>>(relaxed = true)
        every { mockDoc.get() } returns emptyDocTask
        coEvery { emptyDocTask.await() } returns emptyDoc

        val result = repository.ignoreUser(unresolvableId)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { ignoredUserDao.insert(any()) }
        coVerify(exactly = 0) { pendingInteractionDao.replacePendingInteraction(any()) }
        verify(exactly = 0) { InteractionSyncWorker.enqueue(any()) }
    }

    @Test
    fun `ignoreUser self-ignore fails safely`() = runTest {
        val selfPersonaId = "usr_self_123"
        val mockSelfProfile = UserLocalEntity(
            id = "userA",
            anonymousId = selfPersonaId,
            anonymousName = "Self",
            anonymousSigil = "sigil",
            sigilSeed = "seed",
            sigilColor = "#FFD700",
            sigilConfigJson = "{}"
        )
        coEvery { userDao.getProfile("userA") } returns mockSelfProfile

        val result = repository.ignoreUser(selfPersonaId)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { ignoredUserDao.insert(any()) }
        coVerify(exactly = 0) { pendingInteractionDao.replacePendingInteraction(any()) }
    }

    @Test
    fun `unignoreUser with canonical persona ID deletes from Room atomically and enqueues worker`() = runTest {
        val canonicalPersonaId = "usr_target_456"

        val result = repository.unignoreUser(canonicalPersonaId)

        assertTrue(result.isSuccess)
        coVerify { appDatabase.withTransaction(any()) }
        coVerify { ignoredUserDao.delete(canonicalPersonaId, "userA") }
        coVerify {
            pendingInteractionDao.replacePendingInteraction(match {
                it.userId == "userA" &&
                it.artifactId == canonicalPersonaId &&
                it.interactionType == InteractionType.IGNORE &&
                it.action == InteractionAction.REMOVE &&
                it.metadata == canonicalPersonaId
            })
        }
        verify(exactly = 1) { InteractionSyncWorker.enqueue(context) }
        verify(exactly = 0) { mockDoc.delete() } // Direct Firestore delete is deferred to worker
    }

    @Test
    fun `syncIgnoreToFirestore writes directly to private ignored_users collection`() = runTest {
        val targetPersonaId = "usr_remote_target"

        val result = repository.syncIgnoreToFirestore("userA", targetPersonaId)

        assertTrue(result.isSuccess)
        verify { mockDoc.set(any()) }
    }

    @Test
    fun `syncUnignoreFromFirestore deletes directly from private ignored_users collection`() = runTest {
        val targetPersonaId = "usr_remote_target"

        val result = repository.syncUnignoreFromFirestore("userA", targetPersonaId)

        assertTrue(result.isSuccess)
        verify { mockDoc.delete() }
    }

    @Test
    fun `syncIgnoreToFirestore aborts on account ownership mismatch`() = runTest {
        // Authenticated user is "userA", but worker passes "userB"
        val result = repository.syncIgnoreToFirestore("userB", "usr_target")

        assertTrue(result.isFailure)
        verify(exactly = 0) { mockDoc.set(any()) }
    }

    @Test
    fun `syncUnignoreFromFirestore aborts on account ownership mismatch`() = runTest {
        // Authenticated user is "userA", but worker passes "userB"
        val result = repository.syncUnignoreFromFirestore("userB", "usr_target")

        assertTrue(result.isFailure)
        verify(exactly = 0) { mockDoc.delete() }
    }

    @Test
    fun `syncIgnoredUsers preserves pending local ADD and REMOVE during remote refresh`() = runTest {
        val querySnapshot = mockk<QuerySnapshot>()
        val doc1 = mockk<DocumentSnapshot>()
        val doc2 = mockk<DocumentSnapshot>()
        every { doc1.id } returns "usr_remote_1"
        every { doc2.id } returns "usr_to_unignore" // remote still has this, but local has pending REMOVE
        every { querySnapshot.documents } returns listOf(doc1, doc2)
        val queryTask = mockk<Task<QuerySnapshot>>(relaxed = true)
        every { mockColl.get() } returns queryTask
        coEvery { queryTask.await() } returns querySnapshot

        // Local pending operations:
        // 1. Pending REMOVE for "usr_to_unignore"
        // 2. Pending ADD for "usr_local_only"
        val pendingRemove = PendingInteractionEntity(
            userId = "userA",
            artifactId = "usr_to_unignore",
            interactionType = InteractionType.IGNORE,
            action = InteractionAction.REMOVE
        )
        val pendingAdd = PendingInteractionEntity(
            userId = "userA",
            artifactId = "usr_local_only",
            interactionType = InteractionType.IGNORE,
            action = InteractionAction.ADD
        )
        coEvery { pendingInteractionDao.getPendingForUser("userA") } returns listOf(pendingRemove, pendingAdd)

        val result = repository.syncIgnoredUsers()

        assertTrue(result.isSuccess)
        coVerify { ignoredUserDao.deleteAll("userA") }
        // Should insert "usr_remote_1" and "usr_local_only", but NOT "usr_to_unignore"
        coVerify { ignoredUserDao.insert(match { it.userId == "usr_remote_1" }) }
        coVerify { ignoredUserDao.insert(match { it.userId == "usr_local_only" }) }
        coVerify(exactly = 0) { ignoredUserDao.insert(match { it.userId == "usr_to_unignore" }) }
    }
}
