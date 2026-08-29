package com.saurabh.artifact.repository

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.*
import com.saurabh.artifact.data.local.IgnoredUserDao
import com.saurabh.artifact.model.User
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UserRepositoryTotalSilenceTest {
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val ignoredUserDao = mockk<IgnoredUserDao>()
    
    private val mockUsersColl = mockk<CollectionReference>(relaxed = true)
    private val mockUsernamesColl = mockk<CollectionReference>(relaxed = true)

    private lateinit var repository: UserRepository

    @Before
    fun setup() {
        every { firestore.collection("users") } returns mockUsersColl
        every { firestore.collection("usernames") } returns mockUsernamesColl
        
        repository = UserRepository(
            mockk(relaxed = true), mockk(relaxed = true), firestore,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), { ignoredUserDao }, mockk(relaxed = true)
        )
    }

    @Test
    fun `getResonanceUsers filters ignored users but preserves lastVisible`() = runBlocking {
        val userId = "userA"
        val type = "resonance_in"
        
        val snapshot = mockk<QuerySnapshot>()
        val doc1 = mockk<QueryDocumentSnapshot>()
        val doc2 = mockk<QueryDocumentSnapshot>()
        
        every { doc1.id } returns "userB"
        every { doc2.id } returns "userC"
        every { snapshot.documents } returns listOf(doc1, doc2)
        every { snapshot.isEmpty } returns false
        
        val mockInnerColl = mockk<CollectionReference>(relaxed = true)
        val mockQuery = mockk<Query>(relaxed = true)
        
        every { mockUsersColl.document(any()).collection(type) } returns mockInnerColl
        every { mockInnerColl.orderBy(any<String>(), any()) } returns mockQuery
        every { mockQuery.limit(any()) } returns mockQuery
        every { mockQuery.get() } returns Tasks.forResult(snapshot)
        
        coEvery { ignoredUserDao.getAllIgnoredUserIds() } returns listOf("userB")
        
        val userCSnapshot = mockk<QuerySnapshot>()
        val userCDoc = mockk<QueryDocumentSnapshot>()
        every { userCDoc.toObject(User::class.java) } returns User(id = "userC", anonymousName = "User C")
        every { userCDoc.id } returns "userC"
        every { userCSnapshot.documents } returns listOf(userCDoc)
        
        val mockWhereQuery = mockk<Query>(relaxed = true)
        every { mockUsersColl.whereIn(any<FieldPath>(), any<List<String>>()) } returns mockWhereQuery
        every { mockWhereQuery.get() } returns Tasks.forResult(userCSnapshot)

        val result = repository.getResonanceUsers(userId, type)
        
        assertTrue("Expected success but got failure: ${result.exceptionOrNull()}", result.isSuccess)
        val (users, lastVisible) = result.getOrThrow()
        
        assertEquals(1, users.size)
        assertEquals("userC", users[0].id)
        assertEquals(doc2, lastVisible)
    }

    @Test
    fun `getResonanceUsers returns empty list if all users are ignored`() = runBlocking {
        val userId = "userA"
        val type = "resonance_in"
        
        val snapshot = mockk<QuerySnapshot>()
        val doc1 = mockk<QueryDocumentSnapshot>()
        every { doc1.id } returns "userB"
        every { snapshot.documents } returns listOf(doc1)
        every { snapshot.isEmpty } returns false
        
        val mockInnerColl = mockk<CollectionReference>(relaxed = true)
        val mockQuery = mockk<Query>(relaxed = true)
        
        every { mockUsersColl.document(any()).collection(type) } returns mockInnerColl
        every { mockInnerColl.orderBy(any<String>(), any()) } returns mockQuery
        every { mockQuery.limit(any()) } returns mockQuery
        every { mockQuery.get() } returns Tasks.forResult(snapshot)
        
        coEvery { ignoredUserDao.getAllIgnoredUserIds() } returns listOf("userB")

        val result = repository.getResonanceUsers(userId, type)
        
        assertTrue(result.isSuccess)
        val (users, lastVisible) = result.getOrThrow()
        
        assertTrue(users.isEmpty())
        assertEquals(doc1, lastVisible)
    }

    @Test
    fun `getArtifactResonators filters ignored users`() = runBlocking {
        val artifactId = "art123"
        
        val snapshot = mockk<QuerySnapshot>()
        val doc1 = mockk<QueryDocumentSnapshot>()
        val doc2 = mockk<QueryDocumentSnapshot>()
        
        every { doc1.getString("userId") } returns "userB"
        every { doc2.getString("userId") } returns "userC"
        every { snapshot.documents } returns listOf(doc1, doc2)
        every { snapshot.isEmpty } returns false
        
        val mockReactionsColl = mockk<CollectionReference>(relaxed = true)
        val mockQuery = mockk<Query>(relaxed = true)
        
        every { firestore.collection("artifact_reactions") } returns mockReactionsColl
        every { mockReactionsColl.whereEqualTo(any<String>(), any()) } returns mockQuery
        every { mockQuery.orderBy(any<String>(), any()) } returns mockQuery
        every { mockQuery.limit(any()) } returns mockQuery
        every { mockQuery.get() } returns Tasks.forResult(snapshot)
        
        coEvery { ignoredUserDao.getAllIgnoredUserIds() } returns listOf("userB")
        
        val userCSnapshot = mockk<QuerySnapshot>()
        val userCDoc = mockk<QueryDocumentSnapshot>()
        every { userCDoc.toObject(User::class.java) } returns User(id = "userC", anonymousName = "User C")
        every { userCDoc.id } returns "userC"
        every { userCSnapshot.documents } returns listOf(userCDoc)
        
        val mockWhereQuery = mockk<Query>(relaxed = true)
        every { mockUsersColl.whereIn(any<FieldPath>(), any<List<String>>()) } returns mockWhereQuery
        every { mockWhereQuery.get() } returns Tasks.forResult(userCSnapshot)

        val result = repository.getArtifactResonators(artifactId)
        
        assertTrue(result.isSuccess)
        val (users, lastVisible) = result.getOrThrow()
        
        assertEquals(1, users.size)
        assertEquals("userC", users[0].id)
        assertEquals(doc2, lastVisible)
    }
}
