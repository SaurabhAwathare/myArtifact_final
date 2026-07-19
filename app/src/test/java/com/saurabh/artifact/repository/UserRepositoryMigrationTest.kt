package com.saurabh.artifact.repository

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.domain.auth.ProfileRepairService
import com.saurabh.artifact.domain.auth.RegistrationCoordinator
import com.saurabh.artifact.model.User
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Before
import org.junit.Test
import java.util.UUID

class UserRepositoryMigrationTest {
    private val context = mockk<Context>(relaxed = true)
    private val auth = mockk<FirebaseAuth>(relaxed = true)
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val userDao = mockk<com.saurabh.artifact.data.local.UserDao>(relaxed = true)
    private val identityProtectionPolicy = mockk<com.saurabh.artifact.domain.IdentityProtectionPolicy>(relaxed = true)
    private val profileRepairService = mockk<ProfileRepairService>(relaxed = true)
    private val registrationCoordinator = mockk<RegistrationCoordinator>(relaxed = true)
    private val pendingInteractionDao = mockk<com.saurabh.artifact.data.local.PendingInteractionDao>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var userRepository: UserRepository

    @Before
    fun setup() {
        userRepository = UserRepository(
            context = context,
            auth = auth,
            firestore = firestore,
            userDao = dagger.Lazy { userDao },
            identityProtectionPolicy = identityProtectionPolicy,
            profileRepairService = profileRepairService,
            registrationCoordinator = dagger.Lazy { registrationCoordinator },
            pendingInteractionDao = dagger.Lazy { pendingInteractionDao },
            diagnosticLogger = diagnosticLogger
        )
    }

    @Test
    fun `getOrCreateProfile triggers migration when sensitive fields exist at root`() = runBlocking {
        val uid = "test-uid"
        val mockUser = mockk<FirebaseUser>()
        every { mockUser.uid } returns uid
        every { mockUser.email } returns "test@example.com"
        every { mockUser.displayName } returns "Test User"
        every { auth.currentUser } returns mockUser
        
        // Task mocks for tasks.await()
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        
        val userRef = mockk<DocumentReference>(relaxed = true)
        val privateRef = mockk<DocumentReference>(relaxed = true)
        val snapshot = mockk<DocumentSnapshot>(relaxed = true)
        val privateSnapshot = mockk<DocumentSnapshot>(relaxed = true)
        val transaction = mockk<Transaction>(relaxed = true)

        every { firestore.collection("users").document(uid) } returns userRef
        every { userRef.collection("private").document("settings") } returns privateRef
        
        // Mock transaction behavior
        every { firestore.runTransaction<ProfileResult>(any()) } answers {
            val function = firstArg<Transaction.Function<ProfileResult>>()
            val result = function.apply(transaction)
            val mockTask = mockk<com.google.android.gms.tasks.Task<ProfileResult>>()
            coEvery { mockTask.await() } returns (result as ProfileResult)
            mockTask
        }

        every { transaction.get(userRef) } returns snapshot
        every { transaction.get(privateRef) } returns privateSnapshot
        every { snapshot.exists() } returns true
        every { privateSnapshot.exists() } returns true
        
        // Simulate sensitive fields at root
        every { snapshot.get("email") } returns "old@example.com"
        every { snapshot.get("fcmToken") } returns "old-token"
        
        every { profileRepairService.loadAndRepair(snapshot) } returns (User(id = uid) to false)

        userRepository.getOrCreateProfile()

        // Verify migration
        verify {
            transaction.set(privateRef, match<Map<String, Any>> { 
                it["email"] as? String == "old@example.com" && it["fcmToken"] as? String == "old-token"
            }, SetOptions.merge())
            
            transaction.update(userRef, match<Map<String, Any>> {
                it["email"] is FieldValue && it["fcmToken"] is FieldValue
            })
        }
        
        verify { diagnosticLogger.info(any(), "SENSITIVE_DATA_MIGRATED", any()) }
    }

    @Test
    fun `getOrCreateProfile does not trigger migration when fields are absent`() = runBlocking {
        val uid = "test-uid"
        val mockUser = mockk<FirebaseUser>()
        every { mockUser.uid } returns uid
        every { auth.currentUser } returns mockUser
        
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")

        val userRef = mockk<DocumentReference>(relaxed = true)
        val privateRef = mockk<DocumentReference>(relaxed = true)
        val snapshot = mockk<DocumentSnapshot>(relaxed = true)
        val privateSnapshot = mockk<DocumentSnapshot>(relaxed = true)
        val transaction = mockk<Transaction>(relaxed = true)

        every { firestore.collection("users").document(uid) } returns userRef
        every { userRef.collection("private").document("settings") } returns privateRef
        
        every { firestore.runTransaction<ProfileResult>(any()) } answers {
            val function = firstArg<Transaction.Function<ProfileResult>>()
            val result = function.apply(transaction)
            mockk<com.google.android.gms.tasks.Task<ProfileResult>> {
                coEvery { await() } returns (result as ProfileResult)
            }
        }

        every { transaction.get(userRef) } returns snapshot
        every { transaction.get(privateRef) } returns privateSnapshot
        every { snapshot.exists() } returns true
        
        // No sensitive fields
        every { snapshot.get(any<String>()) } returns null
        
        every { profileRepairService.loadAndRepair(snapshot) } returns (User(id = uid) to false)

        userRepository.getOrCreateProfile()

        // Verify no migration
        verify(exactly = 0) {
            transaction.set(privateRef, any<Map<String, Any>>(), SetOptions.merge())
            transaction.update(userRef, any<Map<String, Any>>())
        }
    }
}
