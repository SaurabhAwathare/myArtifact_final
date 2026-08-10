package com.saurabh.artifact.repository

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import com.saurabh.artifact.data.local.UserDao
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.domain.IdentityProtectionPolicy
import com.saurabh.artifact.domain.auth.RegistrationCoordinator
import com.saurabh.artifact.model.User
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

import kotlinx.coroutines.tasks.await
import io.mockk.coEvery

class UserRepositoryTest {
    private val context = mockk<Context>()
    private val auth = mockk<FirebaseAuth>()
    private val firestore = mockk<FirebaseFirestore>()
    private val userDao = mockk<UserDao>(relaxed = true)
    private val identityPolicy = mockk<IdentityProtectionPolicy>()
    private val regCoordinator = mockk<RegistrationCoordinator>()
    private val logger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var repository: UserRepository

    @Before
    fun setup() {
        every { firestore.collection(any()) } returns mockk(relaxed = true)
        
        repository = UserRepository(
            context, auth, firestore,
            Lazy { userDao },
            identityPolicy,
            Lazy { regCoordinator },
            mockk(relaxed = true), // pendingInteractionDao
            logger
        )
    }

    @Test
    fun `getOrCreateProfile repairs Zombie Profile missing identity fields`() = runBlocking {
        // Setup mocks
        val userId = "user123"
        val firebaseUser = mockk<FirebaseUser>()
        every { firebaseUser.uid } returns userId
        every { firebaseUser.email } returns "test@example.com"
        every { firebaseUser.displayName } returns "Test User"
        every { firebaseUser.reload() } returns mockk(relaxed = true)
        every { auth.currentUser } returns firebaseUser

        val userRef = mockk<DocumentReference>()
        val privateRef = mockk<DocumentReference>()
        val snapshot = mockk<DocumentSnapshot>()
        val privateSnapshot = mockk<DocumentSnapshot>()

        every { firestore.collection("users").document(userId) } returns userRef
        every { userRef.collection("private").document("settings") } returns privateRef
        
        // Zombie Profile: fields are present but blank (as per Firestore behavior with defaults)
        val incompleteUser = User(id = userId, anonymousName = "", anonymousId = "", anonymousSigil = "", sigilSeed = "")
        every { snapshot.exists() } returns true
        every { snapshot.toObject(User::class.java) } returns incompleteUser
        every { snapshot.id } returns userId
        every { snapshot.get(any<String>()) } returns null
        
        every { privateSnapshot.exists() } returns true
        
        val transaction = mockk<Transaction>()
        every { transaction.get(userRef) } returns snapshot
        every { transaction.get(privateRef) } returns privateSnapshot
        
        val updates = slot<Map<String, Any>>()
        every { transaction.update(userRef, capture(updates)) } returns transaction
        
        val task = mockk<Task<Any>>()
        every { firestore.runTransaction<Any>(any()) } answers {
            val block = firstArg<Transaction.Function<Any>>()
            val result = block.apply(transaction)
            every { task.isComplete } returns true
            every { task.isSuccessful } returns true
            every { task.result } returns result
            every { task.exception } returns null
            task
        }
        
        // Mocking Task.await() extension function is tricky, usually handled by mockkStatic
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        // No direct await mock needed if we use runBlocking and Task mock correctly if await is called on it.
        // Actually Task.await() needs to be mocked.
        
        coEvery { task.await() } returns mockk<ProfileResult>()

        // When
        val result = repository.getOrCreateProfile()
        
        // Then
        val repairedUser = result.getOrNull()?.user
        assertNotEquals("", repairedUser?.anonymousName)
        assertNotEquals("", repairedUser?.anonymousId)
        assertNotEquals("", repairedUser?.anonymousSigil)
        assertNotEquals("", repairedUser?.sigilSeed)
        assertEquals(repairedUser?.sigilSeed, repairedUser?.sigilConfig?.seed)
        
        verify { transaction.update(userRef, any<Map<String, Any>>()) }
        val capturedUpdates = updates.captured
        assertEquals(repairedUser?.anonymousName, capturedUpdates["anonymousName"])
        assertEquals(repairedUser?.anonymousId, capturedUpdates["anonymousId"])
        assertEquals(repairedUser?.anonymousSigil, capturedUpdates["anonymousSigil"])
        assertEquals(repairedUser?.sigilSeed, capturedUpdates["sigilSeed"])
        assertEquals(repairedUser?.sigilSeed, capturedUpdates["sigilConfig.seed"])
        
        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }
}
