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
import org.junit.Before
import org.junit.Test
import com.google.android.gms.tasks.Tasks
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

    private val mockDoc = mockk<DocumentReference>(relaxed = true)
    private val mockColl = mockk<CollectionReference>(relaxed = true)
    private val mockTask = Tasks.forResult<Void?>(null)

    private lateinit var repository: UserRepository

    @Before
    fun setup() {
        val firebaseUser = mockk<FirebaseUser>()
        every { firebaseUser.uid } returns "userA"
        every { auth.currentUser } returns firebaseUser
        
        // Provide consistent mocks to avoid hangs
        every { mockDoc.set(any()) } returns mockTask
        every { mockDoc.delete() } returns mockTask
        every { mockDoc.collection(any()) } returns mockColl
        every { mockColl.document(any()) } returns mockDoc
        every { firestore.collection(any()) } returns mockColl
        
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

        // Action
        repository.ignoreUser(targetUid)

        // Verify
        verify { mockDoc.set(any()) }
        coVerify { ignoredUserDao.insert(match { it.userId == targetUid }) }
    }

    @Test
    fun `unignoreUser should delete from Firestore and Room`() = runBlocking {
        // Setup
        val targetUid = "userB"

        // Action
        repository.unignoreUser(targetUid)

        // Verify
        verify { mockDoc.delete() }
        coVerify { ignoredUserDao.delete(targetUid) }
    }
}
