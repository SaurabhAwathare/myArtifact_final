package com.saurabh.artifact.domain.auth

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileHealthCheckerTest {

    private val auth = mockk<FirebaseAuth>()
    private val firestore = mockk<FirebaseFirestore>()
    private val firebaseUser = mockk<FirebaseUser>()
    
    private lateinit var profileHealthChecker: ProfileHealthChecker

    @Before
    fun setup() {
        clearMocks(auth, firestore, firebaseUser)

        profileHealthChecker = ProfileHealthChecker(auth, firestore)
        every { auth.currentUser } returns firebaseUser
        every { firebaseUser.uid } returns "test_uid"
    }

    private fun mockPermissionDeniedException(): FirebaseFirestoreException {
        return FirebaseFirestoreException(
            "Permission denied",
            FirebaseFirestoreException.Code.PERMISSION_DENIED
        )
    }

    private fun mockUnavailableException(): FirebaseFirestoreException {
        return FirebaseFirestoreException(
            "Service unavailable",
            FirebaseFirestoreException.Code.UNAVAILABLE
        )
    }

    @Test
    fun `checkHealth returns Terminated when accountStatus is TERMINATED`() = runTest {
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
        verify(exactly = 1) { userRef.get() }
        verify(exactly = 0) { firebaseUser.getIdToken(any()) }
    }

    @Test
    fun `checkHealth returns Healthy when accountStatus is ACTIVE without extra retries`() = runTest {
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
        every { privateSnapshot.toObject(UserPrivateSettings::class.java) } returns UserPrivateSettings(accountStatus = "ACTIVE")

        val result = profileHealthChecker.checkHealth()
        assertEquals(HealthStatus.Healthy, result)
        verify(exactly = 1) { userRef.get() }
        verify(exactly = 0) { firebaseUser.getIdToken(any()) }
    }

    @Test
    fun `checkHealth retries on initial PERMISSION_DENIED and returns Healthy if retry succeeds`() = runTest {
        val userRef = mockk<DocumentReference>()
        val privateRef = mockk<DocumentReference>()
        val userSnapshot = mockk<DocumentSnapshot>()
        val privateSnapshot = mockk<DocumentSnapshot>()

        every { firestore.collection("users").document("test_uid") } returns userRef
        every { userRef.collection("private").document("settings") } returns privateRef

        val permissionDeniedException = mockPermissionDeniedException()

        val failedTask = mockk<Task<DocumentSnapshot>>()
        every { failedTask.isComplete } returns true
        every { failedTask.isSuccessful } returns false
        every { failedTask.isCanceled } returns false
        every { failedTask.exception } returns permissionDeniedException

        val successUserTask = mockk<Task<DocumentSnapshot>>()
        every { successUserTask.isComplete } returns true
        every { successUserTask.isSuccessful } returns true
        every { successUserTask.isCanceled } returns false
        every { successUserTask.result } returns userSnapshot
        every { successUserTask.exception } returns null

        val successPrivateTask = mockk<Task<DocumentSnapshot>>()
        every { successPrivateTask.isComplete } returns true
        every { successPrivateTask.isSuccessful } returns true
        every { successPrivateTask.isCanceled } returns false
        every { successPrivateTask.result } returns privateSnapshot
        every { successPrivateTask.exception } returns null

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
        verify(exactly = 2) { userRef.get() }
        verify(exactly = 0) { firebaseUser.getIdToken(any()) }
    }

    @Test
    fun `checkHealth returns PermissionDenied and bounds retries on persistent PERMISSION_DENIED without force token refresh`() = runTest {
        val userRef = mockk<DocumentReference>()
        val privateRef = mockk<DocumentReference>()
        every { firestore.collection("users").document("test_uid") } returns userRef
        every { userRef.collection("private").document("settings") } returns privateRef

        val permissionDeniedException = mockPermissionDeniedException()

        val failedTask = mockk<Task<DocumentSnapshot>>()
        every { failedTask.isComplete } returns true
        every { failedTask.isSuccessful } returns false
        every { failedTask.isCanceled } returns false
        every { failedTask.exception } returns permissionDeniedException

        // All calls fail with PERMISSION_DENIED
        every { userRef.get() } returns failedTask

        val result = profileHealthChecker.checkHealth()
        assertTrue(result is HealthStatus.PermissionDenied)
        assertEquals(permissionDeniedException, (result as HealthStatus.PermissionDenied).cause)
        // Bounded to 5 attempts
        verify(exactly = 5) { userRef.get() }
        // Verify getIdToken is NOT invoked by the recovery path
        verify(exactly = 0) { firebaseUser.getIdToken(any()) }
    }

    @Test
    fun `checkHealth aborts and returns Missing when currentUser uid changes during retries`() = runTest {
        val userRef = mockk<DocumentReference>()
        val privateRef = mockk<DocumentReference>()
        every { firestore.collection("users").document("test_uid") } returns userRef
        every { userRef.collection("private").document("settings") } returns privateRef

        val permissionDeniedException = mockPermissionDeniedException()

        val failedTask = mockk<Task<DocumentSnapshot>>()
        every { failedTask.isComplete } returns true
        every { failedTask.isSuccessful } returns false
        every { failedTask.isCanceled } returns false
        every { failedTask.exception } returns permissionDeniedException

        every { userRef.get() } returns failedTask

        // Return firebaseUser for initial checks on attempt 1, then otherUser on attempt 2
        val otherUser = mockk<FirebaseUser>()
        every { otherUser.uid } returns "different_uid"
        every { auth.currentUser } returns firebaseUser andThen firebaseUser andThen firebaseUser andThen otherUser

        val result = profileHealthChecker.checkHealth()
        assertEquals(HealthStatus.Missing, result)
        verify(exactly = 1) { userRef.get() }
        verify(exactly = 0) { firebaseUser.getIdToken(any()) }
    }

    @Test
    fun `checkHealth does not retry unrelated errors`() = runTest {
        val userRef = mockk<DocumentReference>()
        val privateRef = mockk<DocumentReference>()
        every { firestore.collection("users").document("test_uid") } returns userRef
        every { userRef.collection("private").document("settings") } returns privateRef

        val unavailableException = mockUnavailableException()

        val failedTask = mockk<Task<DocumentSnapshot>>()
        every { failedTask.isComplete } returns true
        every { failedTask.isSuccessful } returns false
        every { failedTask.isCanceled } returns false
        every { failedTask.exception } returns unavailableException

        every { userRef.get() } returns failedTask

        val result = profileHealthChecker.checkHealth()
        assertEquals(HealthStatus.Missing, result)
        // Only 1 attempt made
        verify(exactly = 1) { userRef.get() }
        verify(exactly = 0) { firebaseUser.getIdToken(any()) }
    }

    @Test
    fun `checkHealth returns Unrecoverable when exception is FirebaseAuthInvalidUserException`() = runTest {
        val userRef = mockk<DocumentReference>()
        val privateRef = mockk<DocumentReference>()
        every { firestore.collection("users").document("test_uid") } returns userRef
        every { userRef.collection("private").document("settings") } returns privateRef

        val userRevokedException = FirebaseAuthInvalidUserException("ERROR_USER_NOT_FOUND", "User revoked")

        val failedTask = mockk<Task<DocumentSnapshot>>()
        every { failedTask.isComplete } returns true
        every { failedTask.isSuccessful } returns false
        every { failedTask.isCanceled } returns false
        every { failedTask.exception } returns userRevokedException

        every { userRef.get() } returns failedTask

        val result = profileHealthChecker.checkHealth()
        assertEquals(HealthStatus.Unrecoverable, result)
        verify(exactly = 1) { userRef.get() }
        verify(exactly = 0) { firebaseUser.getIdToken(any()) }
    }
}
