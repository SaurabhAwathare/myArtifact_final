package com.saurabh.artifact.repository

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.model.CommunityAtmosphere
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeoutException

class CommunityRepositoryTest {

    private val firestore = mockk<FirebaseFirestore>()
    private val repository = CommunityRepository(firestore)

    @Test
    fun `getLatestAtmosphere returns object on success`() = runTest {
        val mockDoc = mockk<DocumentSnapshot>()
        val mockAtmosphere = CommunityAtmosphere(totalArtifacts = 42L, status = "ACTIVE")
        
        every { mockDoc.exists() } returns true
        every { mockDoc.toObject(CommunityAtmosphere::class.java) } returns mockAtmosphere
        
        val mockRef = mockk<DocumentReference>()
        every { firestore.collection("community").document("atmosphere") } returns mockRef
        every { mockRef.get() } returns Tasks.forResult(mockDoc)
        
        val result = repository.getLatestAtmosphere()
        
        assertTrue(result.isSuccess)
        assertEquals(42L, result.getOrNull()?.totalArtifacts)
        assertEquals("ACTIVE", result.getOrNull()?.status)
    }

    @Test
    fun `getLatestAtmosphere returns insufficient data when document missing`() = runTest {
        val mockDoc = mockk<DocumentSnapshot>()
        every { mockDoc.exists() } returns false
        every { mockDoc.toObject(CommunityAtmosphere::class.java) } returns null
        
        val mockRef = mockk<DocumentReference>()
        every { firestore.collection("community").document("atmosphere") } returns mockRef
        every { mockRef.get() } returns Tasks.forResult(mockDoc)
        
        val result = repository.getLatestAtmosphere()
        
        assertTrue(result.isSuccess)
        assertEquals("INSUFFICIENT_DATA", result.getOrNull()?.status)
    }

    @Test
    fun `getLatestAtmosphere returns unauthenticated status when user is null`() = runTest {
        val authRepo = mockk<AuthRepository>()
        coEvery { authRepo.awaitAuthRestoration(any()) } returns true
        every { authRepo.currentUser } returns MutableStateFlow(null)

        val unauthRepo = CommunityRepository(firestore, authRepository = authRepo)
        val result = unauthRepo.getLatestAtmosphere()

        assertTrue(result.isSuccess)
        assertEquals("UNAUTHENTICATED", result.getOrNull()?.status)
        verify(exactly = 0) { firestore.collection("community") }
    }

    @Test
    fun `getLatestAtmosphere fetches atmosphere when user is authenticated`() = runTest {
        val authRepo = mockk<AuthRepository>()
        val mockUser = mockk<FirebaseUser>()
        coEvery { authRepo.awaitAuthRestoration(any()) } returns true
        every { authRepo.currentUser } returns MutableStateFlow(mockUser)

        val mockDoc = mockk<DocumentSnapshot>()
        val mockAtmosphere = CommunityAtmosphere(totalArtifacts = 10L, status = "ACTIVE")
        every { mockDoc.exists() } returns true
        every { mockDoc.toObject(CommunityAtmosphere::class.java) } returns mockAtmosphere

        val mockRef = mockk<DocumentReference>()
        every { firestore.collection("community").document("atmosphere") } returns mockRef
        every { mockRef.get() } returns Tasks.forResult(mockDoc)

        val authRepoGated = CommunityRepository(firestore, authRepository = authRepo)
        val result = authRepoGated.getLatestAtmosphere()

        assertTrue(result.isSuccess)
        assertEquals("ACTIVE", result.getOrNull()?.status)
    }

    @Test
    fun `getLatestAtmosphere returns failure on auth restoration timeout without reading firestore`() = runTest {
        val authRepo = mockk<AuthRepository>()
        coEvery { authRepo.awaitAuthRestoration(any()) } returns false

        val repo = CommunityRepository(firestore, authRepository = authRepo)
        val result = repo.getLatestAtmosphere(timeoutMs = 100L)

        assertTrue("Expected failure on timeout", result.isFailure)
        assertTrue(result.exceptionOrNull() is TimeoutException)
        verify(exactly = 0) { firestore.collection("community") }
    }

    @Test
    fun `getLatestAtmosphere rethrows CancellationException`() = runTest {
        val authRepo = mockk<AuthRepository>()
        coEvery { authRepo.awaitAuthRestoration(any()) } throws CancellationException("Job cancelled")

        val repo = CommunityRepository(firestore, authRepository = authRepo)
        try {
            repo.getLatestAtmosphere()
            assertTrue("Expected CancellationException", false)
        } catch (e: CancellationException) {
            assertEquals("Job cancelled", e.message)
        }
    }
}
