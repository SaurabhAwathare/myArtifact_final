package com.saurabh.artifact.repository

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QueryDocumentSnapshot
import com.google.firebase.firestore.QuerySnapshot
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.domain.ArtifactVisibilityFilter
import com.saurabh.artifact.domain.SafetyPolicy
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.service.RecommendationService
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedRepositoryTest {

    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val recommendationService = mockk<RecommendationService>(relaxed = true)
    private val visibilityFilter = mockk<ArtifactVisibilityFilter>(relaxed = true)
    private val safetyPolicy = SafetyPolicy()
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private val userId = "test_user"

    private lateinit var repository: FeedRepository

    @Before
    fun setup() {
        repository = FeedRepository(
            firestore = firestore,
            artifactRepository = artifactRepository,
            recommendationService = recommendationService,
            visibilityFilter = visibilityFilter,
            safetyPolicy = safetyPolicy,
            diagnosticLogger = diagnosticLogger
        )
        repository.clearDiscoveryBuffer()
    }

    private fun createMockDoc(
        id: String,
        emotion: String = "Calm"
    ): QueryDocumentSnapshot {
        val doc = mockk<QueryDocumentSnapshot>(relaxed = true)
        val artifact = Artifact(
            id = id,
            userId = "user_1",
            title = "Artifact $id",
            audioUrl = "https://example.com/audio_$id.mp3",
            emotion = emotion,
            isPublic = true,
            status = ArtifactStatus.ACTIVE
        )
        every { doc.id } returns id
        every { doc.toObject(Artifact::class.java) } returns artifact
        every { doc.getLong("reportCount") } returns 0L
        every { doc.getLong("safetyConcernCount") } returns 0L
        return doc
    }

    private fun createMockSnapshot(docs: List<DocumentSnapshot>): QuerySnapshot {
        val snapshot = mockk<QuerySnapshot>(relaxed = true)
        every { snapshot.documents } returns docs
        every { snapshot.isEmpty } returns docs.isEmpty()
        return snapshot
    }

    @Test
    fun `getResonatingArtifacts should limit followed user scan to 50`() = runTest {
        val resonanceOutRef = mockk<CollectionReference>(relaxed = true)
        val query = mockk<Query>(relaxed = true)
        val snapshot = mockk<QuerySnapshot>(relaxed = true)

        every { firestore.collection("users").document(userId).collection("resonance_out") } returns resonanceOutRef
        every { resonanceOutRef.orderBy("createdAt", any()) } returns query
        every { query.limit(50) } returns query

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        val task = mockk<Task<QuerySnapshot>>(relaxed = true)
        every { query.get() } returns task
        coEvery { task.await() } returns snapshot
        every { snapshot.documents } returns emptyList()
        every { snapshot.isEmpty } returns true

        repository.getResonatingArtifacts(userId)

        verify { query.limit(50) }

        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `getDiscoveryCandidates with 50 candidates and limit 5 buffers and does not skip candidates`() = runTest {
        val docs = (1..50).map { createMockDoc("doc_$it") }
        val snapshot1 = createMockSnapshot(docs)
        val lastDoc50 = docs.last()

        val collectionRef = mockk<CollectionReference>(relaxed = true)
        val query = mockk<Query>(relaxed = true)
        val task1 = mockk<Task<QuerySnapshot>>(relaxed = true)

        every { firestore.collection("artifacts") } returns collectionRef
        every { collectionRef.whereEqualTo("isPublic", true) } returns query
        every { query.whereEqualTo("status", ArtifactStatus.ACTIVE.name) } returns query
        every { query.orderBy("createdAt", Query.Direction.DESCENDING) } returns query
        every { query.limit(50) } returns query
        every { query.get() } returns task1

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { task1.await() } returns snapshot1
        coEvery { recommendationService.rank(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            it.invocation.args[0] as List<Artifact>
        }

        // Page 1: limit 5
        val result1 = repository.getDiscoveryCandidates(userId, limit = 5, lastVisible = null).getOrThrow()
        assertEquals(5, result1.artifacts.size)
        assertEquals((1..5).map { "doc_$it" }, result1.artifacts.map { it.id })
        assertEquals(lastDoc50, result1.lastVisible)

        // Page 2: limit 5 with lastVisible = lastDoc50 (should serve next 5 from buffer without querying Firestore again)
        val result2 = repository.getDiscoveryCandidates(userId, limit = 5, lastVisible = lastDoc50).getOrThrow()
        assertEquals(5, result2.artifacts.size)
        assertEquals((6..10).map { "doc_$it" }, result2.artifacts.map { it.id })
        assertEquals(lastDoc50, result2.lastVisible)

        // Verify Firestore query.get() was only called ONCE for these 2 pages (served from buffer)
        verify(exactly = 1) { query.get() }

        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `getDiscoveryCandidates with 30 eligible documents paginates all 30 documents and terminates`() = runTest {
        val docs = (1..30).map { createMockDoc("doc_$it") }
        val snapshot1 = createMockSnapshot(docs)
        val lastDoc30 = docs.last()

        val emptySnapshot = createMockSnapshot(emptyList())

        val collectionRef = mockk<CollectionReference>(relaxed = true)
        val query1 = mockk<Query>(relaxed = true)
        val query2 = mockk<Query>(relaxed = true)
        val task1 = mockk<Task<QuerySnapshot>>(relaxed = true)
        val task2 = mockk<Task<QuerySnapshot>>(relaxed = true)

        every { firestore.collection("artifacts") } returns collectionRef
        every { collectionRef.whereEqualTo("isPublic", true) } returns query1
        every { query1.whereEqualTo("status", ArtifactStatus.ACTIVE.name) } returns query1
        every { query1.orderBy("createdAt", Query.Direction.DESCENDING) } returns query1
        every { query1.limit(50) } returns query1
        every { query1.get() } returns task1
        every { query1.startAfter(lastDoc30) } returns query2
        every { query2.limit(50) } returns query2
        every { query2.get() } returns task2

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { task1.await() } returns snapshot1
        coEvery { task2.await() } returns emptySnapshot
        coEvery { recommendationService.rank(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            it.invocation.args[0] as List<Artifact>
        }

        val allEmitted = mutableListOf<String>()
        var currentCursor: DocumentSnapshot? = null

        // Collect 6 pages of 5 items = 30 items
        repeat(6) {
            val res = repository.getDiscoveryCandidates(userId, limit = 5, lastVisible = currentCursor).getOrThrow()
            allEmitted.addAll(res.artifacts.map { it.id })
            currentCursor = res.lastVisible
        }

        assertEquals(30, allEmitted.size)
        assertEquals((1..30).map { "doc_$it" }, allEmitted)

        // Page 7: buffer empty, fetches Firestore starting after lastDoc30 -> returns empty
        val res7 = repository.getDiscoveryCandidates(userId, limit = 5, lastVisible = currentCursor).getOrThrow()
        assertTrue(res7.artifacts.isEmpty())

        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `getDiscoveryCandidates with over 50 documents fetches next pool on buffer depletion`() = runTest {
        val docsPool1 = (1..50).map { createMockDoc("doc_$it") }
        val snapshot1 = createMockSnapshot(docsPool1)
        val lastDoc50 = docsPool1.last()

        val docsPool2 = (51..70).map { createMockDoc("doc_$it") }
        val snapshot2 = createMockSnapshot(docsPool2)
        val lastDoc70 = docsPool2.last()

        val collectionRef = mockk<CollectionReference>(relaxed = true)
        val query1 = mockk<Query>(relaxed = true)
        val query2 = mockk<Query>(relaxed = true)
        val task1 = mockk<Task<QuerySnapshot>>(relaxed = true)
        val task2 = mockk<Task<QuerySnapshot>>(relaxed = true)

        every { firestore.collection("artifacts") } returns collectionRef
        every { collectionRef.whereEqualTo("isPublic", true) } returns query1
        every { query1.whereEqualTo("status", ArtifactStatus.ACTIVE.name) } returns query1
        every { query1.orderBy("createdAt", Query.Direction.DESCENDING) } returns query1
        every { query1.limit(50) } returns query1
        every { query1.get() } returns task1
        every { query1.startAfter(lastDoc50) } returns query2
        every { query2.limit(50) } returns query2
        every { query2.get() } returns task2

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { task1.await() } returns snapshot1
        coEvery { task2.await() } returns snapshot2
        coEvery { recommendationService.rank(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            it.invocation.args[0] as List<Artifact>
        }

        var cursor: DocumentSnapshot? = null
        val emitted = mutableListOf<String>()

        // 10 pages * 5 items = 50 items from Pool 1
        for (i in 1..10) {
            val page = repository.getDiscoveryCandidates(userId, limit = 5, lastVisible = cursor).getOrThrow()
            emitted.addAll(page.artifacts.map { it.id })
            cursor = page.lastVisible
        }

        assertEquals(50, emitted.size)
        assertEquals(lastDoc50, cursor)

        // Page 11: buffer is empty -> queries query2 -> gets Pool 2
        val page11 = repository.getDiscoveryCandidates(userId, limit = 5, lastVisible = cursor).getOrThrow()
        emitted.addAll(page11.artifacts.map { it.id })
        cursor = page11.lastVisible

        assertEquals(55, emitted.size)
        assertEquals("doc_51", page11.artifacts.first().id)
        assertEquals(lastDoc70, cursor)

        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `getDiscoveryCandidates preserves recommendation ranking across candidate pool`() = runTest {
        val docs = (1..10).map { createMockDoc("doc_$it") }
        val snapshot = createMockSnapshot(docs)

        val collectionRef = mockk<CollectionReference>(relaxed = true)
        val query = mockk<Query>(relaxed = true)
        val task = mockk<Task<QuerySnapshot>>(relaxed = true)

        every { firestore.collection("artifacts") } returns collectionRef
        every { collectionRef.whereEqualTo("isPublic", true) } returns query
        every { query.whereEqualTo("status", ArtifactStatus.ACTIVE.name) } returns query
        every { query.orderBy("createdAt", Query.Direction.DESCENDING) } returns query
        every { query.limit(50) } returns query
        every { query.get() } returns task

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { task.await() } returns snapshot
        // Custom ranking: reverse order
        coEvery { recommendationService.rank(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[0] as List<Artifact>).reversed()
        }

        val result = repository.getDiscoveryCandidates(userId, limit = 5, lastVisible = null).getOrThrow()
        assertEquals(listOf("doc_10", "doc_9", "doc_8", "doc_7", "doc_6"), result.artifacts.map { it.id })

        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `getDiscoveryCandidates applies emotion filter when specified and omits filter for All`() = runTest {
        val collectionRef = mockk<CollectionReference>(relaxed = true)
        val query = mockk<Query>(relaxed = true)
        val task = mockk<Task<QuerySnapshot>>(relaxed = true)
        val snapshot = createMockSnapshot(emptyList())

        every { firestore.collection("artifacts") } returns collectionRef
        every { collectionRef.whereEqualTo("isPublic", true) } returns query
        every { query.whereEqualTo("status", ArtifactStatus.ACTIVE.name) } returns query
        every { query.where(any<Filter>()) } returns query
        every { query.orderBy("createdAt", Query.Direction.DESCENDING) } returns query
        every { query.limit(50) } returns query
        every { query.get() } returns task

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { task.await() } returns snapshot

        // Emotion specified as "Calm" -> applies Filter
        repository.getDiscoveryCandidates(userId, limit = 5, emotion = "Calm")
        verify { query.where(any<Filter>()) }

        // Emotion specified as "All" -> does NOT apply Filter
        clearMocks(query, answers = false)
        every { query.whereEqualTo("status", ArtifactStatus.ACTIVE.name) } returns query
        every { query.orderBy("createdAt", Query.Direction.DESCENDING) } returns query
        every { query.limit(50) } returns query
        every { query.get() } returns task

        repository.getDiscoveryCandidates(userId, limit = 5, emotion = "All")
        verify(exactly = 0) { query.where(any<Filter>()) }

        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `getDiscoveryCandidates falls back to cached artifacts on Firestore failure`() = runTest {
        val collectionRef = mockk<CollectionReference>(relaxed = true)
        val query = mockk<Query>(relaxed = true)
        val task = mockk<Task<QuerySnapshot>>(relaxed = true)

        every { firestore.collection("artifacts") } returns collectionRef
        every { collectionRef.whereEqualTo("isPublic", true) } returns query
        every { query.whereEqualTo("status", ArtifactStatus.ACTIVE.name) } returns query
        every { query.orderBy("createdAt", Query.Direction.DESCENDING) } returns query
        every { query.limit(50) } returns query
        every { query.get() } returns task

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { task.await() } throws RuntimeException("Firestore error")

        val cachedList = (1..10).map { Artifact(id = "cached_$it", audioUrl = "http://a.mp3") }
        coEvery { artifactRepository.getRecentCachedArtifacts(userId, null, 50) } returns cachedList
        coEvery { recommendationService.rank(cachedList, userId) } returns cachedList

        val result1 = repository.getDiscoveryCandidates(userId, limit = 5, lastVisible = null).getOrThrow()
        assertEquals(5, result1.artifacts.size)
        assertEquals((1..5).map { "cached_$it" }, result1.artifacts.map { it.id })

        val result2 = repository.getDiscoveryCandidates(userId, limit = 5, lastVisible = null).getOrThrow()
        assertEquals(5, result2.artifacts.size)
        assertEquals((6..10).map { "cached_$it" }, result2.artifacts.map { it.id })

        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }
}
