package com.saurabh.artifact.repository

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.data.local.IgnoredUserDao
import com.saurabh.artifact.data.local.IgnoredUserEntity
import com.saurabh.artifact.data.local.UserDao
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.domain.IdentityProtectionPolicy
import com.saurabh.artifact.domain.auth.RegistrationCoordinator
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Before
import org.junit.Test
import com.google.android.gms.tasks.Task

class UserRepositoryIgnoreTest {
    private val context = mockk<Context>(relaxed = true)
    private val auth = mockk<FirebaseAuth>()
    private val firestore = mockk<FirebaseFirestore>()
    private val userDao = mockk<UserDao>(relaxed = true)
    private val ignoredUserDao = mockk<IgnoredUserDao>(relaxed = true)
    private val identityPolicy = mockk<IdentityProtectionPolicy>()
    private val regCoordinator = mockk<RegistrationCoordinator>()
    private val logger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var repository: UserRepository

    @Before
    fun setup() {
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        val firebaseUser = mockk<FirebaseUser>()
        every { firebaseUser.uid } returns "userA"
        every { auth.currentUser } returns firebaseUser
        
        repository = UserRepository(
            context, auth, firestore,
            Lazy { userDao },
            identityPolicy,
            Lazy { regCoordinator },
            mockk(relaxed = true),
            Lazy { ignoredUserDao },
            logger
        )
    }

    @Test
    fun `ignoreUser should write to Firestore and Room`() = runBlocking {
        // Setup
        val targetUid = "userB"
        val userDoc = mockk<DocumentReference>(relaxed = true)
        val privateDoc = mockk<DocumentReference>(relaxed = true)
        val ignoredCollection = mockk<CollectionReference>(relaxed = true)
        val targetDoc = mockk<DocumentReference>(relaxed = true)
        val setTask = mockk<Task<Void>>(relaxed = true)

        every { firestore.collection("users").document("userA") } returns userDoc
        every { userDoc.collection("private").document("ignored_users") } returns privateDoc
        every { privateDoc.collection("users") } returns ignoredCollection
        every { ignoredCollection.document(targetUid) } returns targetDoc
        every { targetDoc.set(any()) } returns setTask
        coEvery { setTask.await() } returns mockk()

        // Action
        repository.ignoreUser(targetUid)

        // Verify
        verify { targetDoc.set(any<Map<String, Any>>()) }
        coVerify { ignoredUserDao.insert(match { it.userId == targetUid }) }
    }

    @Test
    fun `unignoreUser should delete from Firestore and Room`() = runBlocking {
        // Setup
        val targetUid = "userB"
        val userDoc = mockk<DocumentReference>(relaxed = true)
        val privateDoc = mockk<DocumentReference>(relaxed = true)
        val ignoredCollection = mockk<CollectionReference>(relaxed = true)
        val targetDoc = mockk<DocumentReference>(relaxed = true)
        val deleteTask = mockk<Task<Void>>(relaxed = true)

        every { firestore.collection("users").document("userA") } returns userDoc
        every { userDoc.collection("private").document("ignored_users") } returns privateDoc
        every { privateDoc.collection("users") } returns ignoredCollection
        every { ignoredCollection.document(targetUid) } returns targetDoc
        every { targetDoc.delete() } returns deleteTask
        coEvery { deleteTask.await() } returns mockk()

        // Action
        repository.unignoreUser(targetUid)

        // Verify
        verify { targetDoc.delete() }
        coVerify { ignoredUserDao.delete(targetUid) }
    }
}
