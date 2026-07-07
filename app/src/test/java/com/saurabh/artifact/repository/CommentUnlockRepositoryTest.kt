package com.saurabh.artifact.repository

import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import com.saurabh.artifact.util.ArtifactLogger
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CommentUnlockRepositoryTest {

    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val currentUserFlow = MutableStateFlow<FirebaseUser?>(null)

    private lateinit var repository: CommentUnlockRepository

    @Before
    fun setup() {
        mockkObject(ArtifactLogger)
        every { ArtifactLogger.d(any(), any()) } just runs
        every { ArtifactLogger.e(any(), any(), any()) } just runs
        
        every { authRepository.currentUser } returns currentUserFlow
        
        repository = CommentUnlockRepository(firestore, authRepository)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `unlockedArtifactIds returns empty set when no user`() = runTest {
        currentUserFlow.value = null
        
        val result = repository.unlockedArtifactIds.first()
        
        assertTrue(result.isEmpty())
        verify(exactly = 0) { firestore.collection(any()) }
    }

    @Test
    fun `unlockedArtifactIds attaches listener when user is logged in`() = runTest {
        val userId = "test_user"
        val mockUser = mockk<FirebaseUser> { every { uid } returns userId }
        
        val collectionRef = mockk<CollectionReference>(relaxed = true)
        val documentRef = mockk<DocumentReference>(relaxed = true)
        val subCollectionRef = mockk<CollectionReference>(relaxed = true)
        val query = mockk<Query>(relaxed = true)
        
        every { firestore.collection("users") } returns collectionRef
        every { collectionRef.document(userId) } returns documentRef
        every { documentRef.collection("engagement") } returns subCollectionRef
        every { subCollectionRef.whereEqualTo("isCommentUnlocked", true) } returns query
        
        val registration = mockk<ListenerRegistration>(relaxed = true)
        every { query.addSnapshotListener(any()) } returns registration
        
        currentUserFlow.value = mockUser
        
        // Start collecting in a background job
        val job = launch {
            repository.unlockedArtifactIds.collect { }
        }
        advanceUntilIdle()
        
        verify { query.addSnapshotListener(any()) }
        job.cancel()
    }

    @Test
    fun `unlockedArtifactIds detaches listener when user logs out`() = runTest {
        val userId = "test_user"
        val mockUser = mockk<FirebaseUser> { every { uid } returns userId }
        
        val collectionRef = mockk<CollectionReference>(relaxed = true)
        val documentRef = mockk<DocumentReference>(relaxed = true)
        val subCollectionRef = mockk<CollectionReference>(relaxed = true)
        val query = mockk<Query>(relaxed = true)
        val registration = mockk<ListenerRegistration>(relaxed = true)

        every { firestore.collection("users") } returns collectionRef
        every { collectionRef.document(userId) } returns documentRef
        every { documentRef.collection("engagement") } returns subCollectionRef
        every { subCollectionRef.whereEqualTo("isCommentUnlocked", true) } returns query
        every { query.addSnapshotListener(any()) } returns registration
        
        currentUserFlow.value = mockUser
        
        // Start collecting
        val job = launch {
            repository.unlockedArtifactIds.collect { }
        }
        advanceUntilIdle()
        
        // Verify attached
        verify { query.addSnapshotListener(any()) }
        
        // Simulate logout
        currentUserFlow.value = null
        advanceUntilIdle()
        
        // Verify removal. Note: flatMapLatest should cancel the previous flow, 
        // which triggers awaitClose.
        verify { registration.remove() }
        job.cancel()
    }
}
