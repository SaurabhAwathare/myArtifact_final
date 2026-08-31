package com.saurabh.artifact.repository

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.*
import com.saurabh.artifact.data.local.IgnoredUserDao
import com.saurabh.artifact.data.remote.model.CommentDto
import com.saurabh.artifact.model.CommentStatus
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FirestoreCommentRepositoryTotalSilenceTest {
    private val firestore = mockk<FirebaseFirestore>()
    private val ignoredUserDao = mockk<IgnoredUserDao>()
    private val diagnosticLogger = mockk<com.saurabh.artifact.diagnostics.DiagnosticLogger>(relaxed = true)

    private lateinit var repository: FirestoreCommentRepository

    @Before
    fun setup() {
        repository = FirestoreCommentRepository(
            firestore, { ignoredUserDao }, diagnosticLogger
        )
    }

    @Test
    fun `getComments filters ignored user comments`() = runBlocking {
        val artifactId = "art123"
        val limit = 2
        
        val snapshot = mockk<QuerySnapshot>()
        val doc1 = mockk<QueryDocumentSnapshot>()
        val doc2 = mockk<QueryDocumentSnapshot>()
        
        val dto1 = CommentDto(id = "com1", artifactId = artifactId, authorAnonymousId = "personaB", text = "B", status = "ACTIVE")
        val dto2 = CommentDto(id = "com2", artifactId = artifactId, authorAnonymousId = "personaC", text = "C", status = "ACTIVE")
        
        every { doc1.getString("creatorId") } returns "userB"
        every { doc1.toObject(CommentDto::class.java) } returns dto1
        every { doc1.id } returns "com1"
        
        every { doc2.getString("creatorId") } returns "userC"
        every { doc2.toObject(CommentDto::class.java) } returns dto2
        every { doc2.id } returns "com2"
        
        every { snapshot.documents } returns listOf(doc1, doc2)
        
        val mockColl = mockk<CollectionReference>(relaxed = true)
        every { firestore.collection(any()) } returns mockColl
        val mockQuery = mockk<Query>(relaxed = true)
        every { mockColl.whereIn(any<String>(), any()) } returns mockQuery
        every { mockQuery.orderBy(any<String>(), any()) } returns mockQuery
        every { mockQuery.limit(any()) } returns mockQuery
        
        every { mockQuery.get() } returns Tasks.forResult(snapshot)
        
        coEvery { ignoredUserDao.getAllIgnoredUserIds() } returns listOf("userB")

        val result = repository.getComments(artifactId, limit)
        
        if (result.isFailure) {
            fail("Result was failure: ${result.exceptionOrNull()}")
        }
        
        val paginated = result.getOrThrow()
        
        assertEquals(1, paginated.comments.size)
        assertEquals("com2", paginated.comments[0].id)
        assertEquals(doc2, paginated.lastVisible)
    }
}
