package com.saurabh.artifact.repository

import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.FakeDiagnosticLogger
import com.saurabh.artifact.diagnostics.TestNoOpDiagnosticLogger
import com.saurabh.artifact.domain.auth.ProfileRepairService
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthRepositoryTest {
    private val firebaseAuth = mockk<FirebaseAuth>(relaxed = true)
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val credentialManager = mockk<CredentialManager>(relaxed = true)
    private val profileRepairService = mockk<ProfileRepairService>(relaxed = true)
    private val fakeLogger = FakeDiagnosticLogger()

    private lateinit var repository: AuthRepository

    @Before
    fun setup() {
        ArtifactLogger.init(fakeLogger)

        repository = AuthRepository(
            firebaseAuth = firebaseAuth,
            firestore = firestore,
            credentialManager = credentialManager,
            profileRepairService = profileRepairService
        )
    }

    @After
    fun tearDown() {
        ArtifactLogger.init(TestNoOpDiagnosticLogger)
    }

    @Test
    fun `signOut clears FCM token then signs out`() = runBlocking {
        val uid = "test-uid"
        val mockUser = mockk<FirebaseUser>()
        every { mockUser.uid } returns uid
        every { firebaseAuth.currentUser } returns mockUser

        val userDoc = mockk<DocumentReference>(relaxed = true)
        val privateColl = mockk<CollectionReference>(relaxed = true)
        val settingsDoc = mockk<DocumentReference>(relaxed = true)
        
        every { firestore.collection("users").document(uid) } returns userDoc
        every { userDoc.collection("private") } returns privateColl
        every { privateColl.document("settings") } returns settingsDoc

        val updateTask = mockk<Task<Void>>(relaxed = true)
        every { settingsDoc.update("fcmToken", any()) } returns updateTask
        
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { updateTask.await() } returns mockk()

        // Mock credentialManager.clearCredentialState
        coEvery { credentialManager.clearCredentialState(any()) } returns mockk()

        val result = repository.signOut()

        assertTrue(result.isSuccess)
        
        // Verify individual calls since coVerifyOrder is more robust for suspend functions
        coVerifyOrder {
            settingsDoc.update("fcmToken", FieldValue.delete())
            credentialManager.clearCredentialState(any<ClearCredentialStateRequest>())
            firebaseAuth.signOut()
        }
    }

    @Test
    fun `signOut continues if clearFcmToken fails`() = runBlocking {
        val uid = "test-uid"
        val mockUser = mockk<FirebaseUser>()
        every { mockUser.uid } returns uid
        every { firebaseAuth.currentUser } returns mockUser

        val userDoc = mockk<DocumentReference>(relaxed = true)
        val privateColl = mockk<CollectionReference>(relaxed = true)
        val settingsDoc = mockk<DocumentReference>(relaxed = true)
        
        every { firestore.collection("users").document(uid) } returns userDoc
        every { userDoc.collection("private") } returns privateColl
        every { privateColl.document("settings") } returns settingsDoc

        val updateTask = mockk<Task<Void>>(relaxed = true)
        every { settingsDoc.update("fcmToken", any()) } returns updateTask
        
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { updateTask.await() } throws Exception("Firestore error")

        val result = repository.signOut()

        // Should still succeed because clearFcmToken failure is handled gracefully
        assertTrue(result.isSuccess)
        
        verify { firebaseAuth.signOut() }
        fakeLogger.assertEventExists(DiagnosticCategory.AUTH, "FCM_TOKEN_CLEAR_FAILED")
    }

    @Test
    fun `signOut firestorePermissionDenied signOutStillSucceeds`() = runBlocking {
        val uid = "test-uid"
        val mockUser = mockk<FirebaseUser>()
        every { mockUser.uid } returns uid
        every { firebaseAuth.currentUser } returns mockUser

        val userDoc = mockk<DocumentReference>(relaxed = true)
        val privateColl = mockk<CollectionReference>(relaxed = true)
        val settingsDoc = mockk<DocumentReference>(relaxed = true)
        
        every { firestore.collection("users").document(uid) } returns userDoc
        every { userDoc.collection("private") } returns privateColl
        every { privateColl.document("settings") } returns settingsDoc

        val updateTask = mockk<Task<Void>>(relaxed = true)
        every { settingsDoc.update("fcmToken", any()) } returns updateTask
        
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        // Use mockk for the exception to avoid complex initialization of FirebaseFirestoreException
        val permissionDeniedException = mockk<FirebaseFirestoreException>()
        every { permissionDeniedException.message } returns "Permission denied"
        coEvery { updateTask.await() } throws permissionDeniedException

        val result = repository.signOut()

        assertTrue(result.isSuccess)
        verify { firebaseAuth.signOut() }
        fakeLogger.assertEventExists(DiagnosticCategory.AUTH, "FCM_TOKEN_CLEAR_FAILED")
    }

    @Test
    fun `signOut handles null user`() = runBlocking {
        every { firebaseAuth.currentUser } returns null

        val result = repository.signOut()

        assertTrue(result.isSuccess)
        verify(exactly = 0) { firestore.collection(any()) }
        verify { firebaseAuth.signOut() }
    }
}
