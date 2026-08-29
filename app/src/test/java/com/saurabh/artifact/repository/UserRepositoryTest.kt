package com.saurabh.artifact.repository

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.tasks.await

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
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        
        val mockDoc = mockk<DocumentReference>(relaxed = true)
        val mockColl = mockk<CollectionReference>(relaxed = true)
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
            mockk(relaxed = true),
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
        
        val authException = mockk<com.google.firebase.auth.FirebaseAuthInvalidUserException>(relaxed = true)
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
}
