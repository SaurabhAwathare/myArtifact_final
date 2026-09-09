package com.saurabh.artifact.repository

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.*
import com.saurabh.artifact.data.local.IgnoredUserDao
import com.saurabh.artifact.data.local.InteractionAction
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.data.remote.model.CommentDto
import com.saurabh.artifact.data.remote.model.CommentPayload
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.Comment
import com.saurabh.artifact.model.CommentStatus
import com.saurabh.artifact.worker.InteractionSyncWorker
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FirestoreCommentRepositoryTotalSilenceTest {
    private val context = mockk<Context>(relaxed = true)
    private val firestore = mockk<FirebaseFirestore>()
    private val ignoredUserDao = mockk<IgnoredUserDao>()
    private val pendingInteractionDao = mockk<PendingInteractionDao>()
    private val authRepository = mockk<AuthRepository>()
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var repository: FirestoreCommentRepository

    private companion object {
        private const val TEST_USER_ID = "userA"
    }

    @Before
    fun setup() {
        every { authRepository.currentUserId } returns TEST_USER_ID
        repository = FirestoreCommentRepository(
            context,
            firestore,
            { ignoredUserDao },
            { pendingInteractionDao },
            authRepository,
            diagnosticLogger
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
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
        
        every { doc1.getString("authorAnonymousId") } returns "personaB"
        every { doc1.toObject(CommentDto::class.java) } returns dto1
        every { doc1.id } returns "com1"
        
        every { doc2.getString("authorAnonymousId") } returns "personaC"
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
        
        coEvery { ignoredUserDao.getAllIgnoredUserIds(TEST_USER_ID) } returns listOf("personaB")

        val result = repository.getComments(artifactId, limit)
        
        if (result.isFailure) {
            fail("Result was failure: ${result.exceptionOrNull()}")
        }
        
        val paginated = result.getOrThrow()
        
        assertEquals(1, paginated.comments.size)
        assertEquals("com2", paginated.comments[0].id)
        assertEquals(doc2, paginated.lastVisible)
    }

    @Test
    fun `createComment when authenticated writes to user private intent comments collection`() = runBlocking {
        val comment = Comment(
            id = "comment-123",
            artifactId = "art-1",
            text = "Hello world",
            author = AuthorSnapshot(anonymousId = "personaA")
        )

        val mockUsersColl = mockk<CollectionReference>()
        val mockUserDoc = mockk<DocumentReference>()
        val mockPrivateColl = mockk<CollectionReference>()
        val mockIntentsDoc = mockk<DocumentReference>()
        val mockCommentsColl = mockk<CollectionReference>()
        val mockCommentDoc = mockk<DocumentReference>()

        every { firestore.collection("users") } returns mockUsersColl
        every { mockUsersColl.document(TEST_USER_ID) } returns mockUserDoc
        every { mockUserDoc.collection("private") } returns mockPrivateColl
        every { mockPrivateColl.document("intents") } returns mockIntentsDoc
        every { mockIntentsDoc.collection("comments") } returns mockCommentsColl
        every { mockCommentsColl.document("comment-123") } returns mockCommentDoc
        every { mockCommentDoc.id } returns "comment-123"
        every { mockCommentDoc.set(any()) } returns Tasks.forResult(null)

        val result = repository.createComment(comment)

        assertTrue(result.isSuccess)
        assertEquals("comment-123", result.getOrThrow().id)
        verify { mockCommentDoc.set(match { (it as CommentDto).text == "Hello world" && it.authorAnonymousId == "personaA" }) }
    }

    @Test
    fun `createComment when unauthenticated returns failure Unauthenticated`() = runBlocking {
        every { authRepository.currentUserId } returns ""

        val comment = Comment(id = "comment-123", artifactId = "art-1", text = "Test")
        val result = repository.createComment(comment)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AppError.Unauthenticated)
    }

    @Test
    fun `enqueueComment when authenticated serializes payload and inserts pending interaction`() = runBlocking {
        mockkObject(InteractionSyncWorker)
        every { InteractionSyncWorker.enqueue(any()) } just Runs
        coEvery { pendingInteractionDao.insert(any()) } just Runs

        val comment = Comment(
            id = "comment-123",
            artifactId = "art-1",
            text = "Offline comment",
            author = AuthorSnapshot(anonymousId = "personaA")
        )

        val result = repository.enqueueComment(comment)

        assertTrue(result.isSuccess)

        coVerify {
            pendingInteractionDao.insert(match { pending ->
                pending.userId == TEST_USER_ID &&
                        pending.artifactId == "art-1" &&
                        pending.interactionType == InteractionType.COMMENT &&
                        pending.action == InteractionAction.ADD &&
                        pending.metadata != null &&
                        pending.metadata.contains("Offline comment")
            })
        }
        verify { InteractionSyncWorker.enqueue(context) }
    }

    @Test
    fun `enqueueComment when unauthenticated returns failure Unauthenticated`() = runBlocking {
        every { authRepository.currentUserId } returns ""

        val comment = Comment(id = "comment-123", artifactId = "art-1", text = "Offline comment")
        val result = repository.enqueueComment(comment)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AppError.Unauthenticated)
    }

    @Test
    fun `deleteComment soft deletes comment by updating status in Firestore`() = runBlocking {
        val mockColl = mockk<CollectionReference>()
        val mockDoc = mockk<DocumentReference>()

        every { firestore.collection("artifacts/art-1/comments") } returns mockColl
        every { mockColl.document("com1") } returns mockDoc
        every { mockDoc.update("status", CommentStatus.DELETED.name) } returns Tasks.forResult(null)

        val result = repository.deleteComment("art-1", "com1")

        assertTrue(result.isSuccess)
        verify { mockDoc.update("status", "DELETED") }
    }

    @Test
    fun `CommentPayload serialization roundtrip preserves timestamp precision and fields`() {
        val originalTimestamp = Timestamp(1710000000, 500000000)
        val comment = Comment(
            id = "c1",
            artifactId = "a1",
            author = AuthorSnapshot(anonymousId = "p1", name = "Author 1"),
            text = "Test text",
            createdAt = originalTimestamp,
            updatedAt = originalTimestamp,
            status = CommentStatus.ACTIVE,
            identityVersion = 2L
        )

        val payload = CommentPayload.fromDomain(comment)
        val jsonStr = Json.encodeToString(CommentPayload.serializer(), payload)
        val decodedPayload = Json.decodeFromString(CommentPayload.serializer(), jsonStr)
        val reconstructed = decodedPayload.toDomain()

        assertEquals(comment.id, reconstructed.id)
        assertEquals(comment.artifactId, reconstructed.artifactId)
        assertEquals(comment.text, reconstructed.text)
        assertEquals(comment.author.anonymousId, reconstructed.author.anonymousId)
        assertEquals(comment.author.name, reconstructed.author.name)
        assertEquals(comment.createdAt.seconds, reconstructed.createdAt.seconds)
        assertEquals(comment.createdAt.nanoseconds / 1000000, reconstructed.createdAt.nanoseconds / 1000000)
        assertEquals(comment.status, reconstructed.status)
        assertEquals(comment.identityVersion, reconstructed.identityVersion)
    }
}
