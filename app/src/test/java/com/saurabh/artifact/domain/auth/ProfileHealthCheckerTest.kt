package com.saurabh.artifact.domain.auth

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.saurabh.artifact.model.User
import com.saurabh.artifact.model.UserPrivateSettings
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileHealthCheckerTest {

    private val auth = mockk<FirebaseAuth>()
    private val firestore = mockk<FirebaseFirestore>()
    private val firebaseUser = mockk<FirebaseUser>()
    
    private lateinit var profileHealthChecker: ProfileHealthChecker

    @Before
    fun setup() {
        clearAllMocks()
        profileHealthChecker = ProfileHealthChecker(auth, firestore)
        every { auth.currentUser } returns firebaseUser
        every { firebaseUser.uid } returns "test_uid"
    }

    @Test
    fun `checkHealth returns Terminated when accountStatus is TERMINATED`() = runTest {
        val userRef = mockk<DocumentReference>()
        val privateRef = mockk<DocumentReference>()
        val userSnapshot = mockk<DocumentSnapshot>()
        val privateSnapshot = mockk<DocumentSnapshot>()
        
        every { firestore.collection("users").document("test_uid") } returns userRef
        every { userRef.collection("private").document("settings") } returns privateRef

        // Mock tasks (await() extension)
        // We mock the get().await() behavior by mocking get() to return a successful task
        val userTask = mockk<Task<DocumentSnapshot>>()
        val privateTask = mockk<Task<DocumentSnapshot>>()
        
        every { userRef.get() } returns userTask
        every { privateRef.get() } returns privateTask
        
        // Mock task results
        every { userTask.isComplete } returns true
        every { userTask.isSuccessful } returns true
        every { userTask.isCanceled } returns false
        every { userTask.result } returns userSnapshot
        every { userTask.exception } returns null
        
        every { privateTask.isComplete } returns true
        every { privateTask.isSuccessful } returns true
        every { privateTask.isCanceled } returns false
        every { privateTask.result } returns privateSnapshot
        every { privateTask.exception } returns null

        every { userSnapshot.exists() } returns true
        every { userSnapshot.toObject(User::class.java) } returns User(anonymousId = "id", anonymousName = "name")
        every { userSnapshot.id } returns "test_uid"

        every { privateSnapshot.exists() } returns true
        every { privateSnapshot.toObject(UserPrivateSettings::class.java) } returns UserPrivateSettings(accountStatus = "TERMINATED")

        val result = profileHealthChecker.checkHealth()
        assertEquals(HealthStatus.Terminated, result)
    }

    @Test
    fun `checkHealth returns Healthy when accountStatus is ACTIVE`() = runTest {
        val userRef = mockk<DocumentReference>()
        val privateRef = mockk<DocumentReference>()
        val userSnapshot = mockk<DocumentSnapshot>()
        val privateSnapshot = mockk<DocumentSnapshot>()
        
        every { firestore.collection("users").document("test_uid") } returns userRef
        every { userRef.collection("private").document("settings") } returns privateRef

        val userTask = mockk<Task<DocumentSnapshot>>()
        val privateTask = mockk<Task<DocumentSnapshot>>()
        
        every { userRef.get() } returns userTask
        every { privateRef.get() } returns privateTask
        
        every { userTask.isComplete } returns true
        every { userTask.isSuccessful } returns true
        every { userTask.isCanceled } returns false
        every { userTask.result } returns userSnapshot
        
        every { privateTask.isComplete } returns true
        every { privateTask.isSuccessful } returns true
        every { privateTask.isCanceled } returns false
        every { privateTask.result } returns privateSnapshot

        every { userSnapshot.exists() } returns true
        every { userSnapshot.toObject(User::class.java) } returns User(anonymousId = "id", anonymousName = "name")
        every { userSnapshot.id } returns "test_uid"

        every { privateSnapshot.exists() } returns true
        every { privateSnapshot.toObject(UserPrivateSettings::class.java) } returns UserPrivateSettings(accountStatus = "ACTIVE")

        val result = profileHealthChecker.checkHealth()
        assertEquals(HealthStatus.Healthy, result)
    }

    @Test
    fun `checkHealth retries on PERMISSION_DENIED and returns Healthy if token refresh succeeds`() = runTest {
        val userRef = mockk<DocumentReference>()
        val privateRef = mockk<DocumentReference>()
        val userSnapshot = mockk<DocumentSnapshot>()
        val privateSnapshot = mockk<DocumentSnapshot>()

        every { firestore.collection("users").document("test_uid") } returns userRef
        every { userRef.collection("private").document("settings") } returns privateRef

        val permissionDeniedException = FirebaseFirestoreException(
            "Permission denied",
            FirebaseFirestoreException.Code.PERMISSION_DENIED
        )

        val failedTask = mockk<Task<DocumentSnapshot>>()
        every { failedTask.isComplete } returns true
        every { failedTask.isSuccessful } returns false
        every { failedTask.isCanceled } returns false
        every { failedTask.exception } returns permissionDeniedException

        val tokenTask = mockk<Task<GetTokenResult>>()
        every { tokenTask.isComplete } returns true
        every { tokenTask.isSuccessful } returns true
        every { tokenTask.isCanceled } returns false
        every { tokenTask.result } returns mockk(relaxed = true)
        every { tokenTask.exception } returns null
        every { firebaseUser.getIdToken(true) } returns tokenTask

        val successUserTask = mockk<Task<DocumentSnapshot>>()
        every { successUserTask.isComplete } returns true
        every { successUserTask.isSuccessful } returns true
        every { successUserTask.isCanceled } returns false
        every { successUserTask.result } returns userSnapshot

        val successPrivateTask = mockk<Task<DocumentSnapshot>>()
        every { successPrivateTask.isComplete } returns true
        every { successPrivateTask.isSuccessful } returns true
        every { successPrivateTask.isCanceled } returns false
        every { successPrivateTask.result } returns privateSnapshot

        // First call fails with PERMISSION_DENIED, retry call succeeds
        every { userRef.get() } returns failedTask andThen successUserTask
        every { privateRef.get() } returns successPrivateTask

        every { userSnapshot.exists() } returns true
        every { userSnapshot.toObject(User::class.java) } returns User(anonymousId = "id", anonymousName = "name")
        every { userSnapshot.id } returns "test_uid"

        every { privateSnapshot.exists() } returns true
        every { privateSnapshot.toObject(UserPrivateSettings::class.java) } returns UserPrivateSettings(accountStatus = "ACTIVE")

        val result = profileHealthChecker.checkHealth()
        assertEquals(HealthStatus.Healthy, result)
        verify(exactly = 1) { firebaseUser.getIdToken(true) }
    }

    @Test
    fun `checkHealth returns Unrecoverable on persistent PERMISSION_DENIED`() = runTest {
        val userRef = mockk<DocumentReference>()
        every { firestore.collection("users").document("test_uid") } returns userRef

        val permissionDeniedException = FirebaseFirestoreException(
            "Permission denied",
            FirebaseFirestoreException.Code.PERMISSION_DENIED
        )

        val failedTask = mockk<Task<DocumentSnapshot>>()
        every { failedTask.isComplete } returns true
        every { failedTask.isSuccessful } returns false
        every { failedTask.isCanceled } returns false
        every { failedTask.exception } returns permissionDeniedException

        val tokenTask = mockk<Task<GetTokenResult>>()
        every { tokenTask.isComplete } returns true
        every { tokenTask.isSuccessful } returns true
        every { tokenTask.isCanceled } returns false
        every { tokenTask.result } returns mockk(relaxed = true)
        every { tokenTask.exception } returns null
        every { firebaseUser.getIdToken(true) } returns tokenTask

        // Both initial and retry calls fail with PERMISSION_DENIED
        every { userRef.get() } returns failedTask

        val result = profileHealthChecker.checkHealth()
        assertEquals(HealthStatus.Unrecoverable, result)
        verify(exactly = 1) { firebaseUser.getIdToken(true) }
    }
}
