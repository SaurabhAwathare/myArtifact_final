package com.saurabh.artifact.repository

import android.content.Context
import android.util.Log
import android.util.SparseArray
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import com.saurabh.artifact.audio.LocalDraftManager
import com.saurabh.artifact.domain.prompt.ReflectionPromptManager
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.model.*
import com.saurabh.artifact.service.PersonalizationEngine
import com.saurabh.artifact.service.ReflectionAIService
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.worker.InteractionSyncWorker
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ArtifactRepositoryTest {
    private val context = mockk<Context>(relaxed = true)
    private val auth = mockk<FirebaseAuth>(relaxed = true)
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val storage = mockk<FirebaseStorage>(relaxed = true)
    private val functions = mockk<FirebaseFunctions>(relaxed = true)
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val artifactDao = mockk<ArtifactDao>(relaxed = true)
    private val reportedArtifactDao = mockk<ReportedArtifactDao>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val artifactLibraryRepository = mockk<ArtifactLibraryRepository>(relaxed = true)
    private val moderationRepository = mockk<ArtifactModerationRepository>(relaxed = true)
    private val publishingRepository = mockk<ArtifactPublishingRepository>(relaxed = true)
    private val artifactEngagementRepository = mockk<ArtifactEngagementRepository>(relaxed = true)
    private val reflectionPromptManager = mockk<ReflectionPromptManager>(relaxed = true)
    private val visibilityFilter = mockk<com.saurabh.artifact.domain.ArtifactVisibilityFilter>(relaxed = true)
    private val localDraftManager = mockk<LocalDraftManager>(relaxed = true)
    private val pendingInteractionDao = mockk<PendingInteractionDao>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var repository: ArtifactRepository

    private companion object {
        private const val TEST_USER_ID = "test-user-id"
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        
        repository = ArtifactRepository(
            auth = auth,
            firestore = firestore,
            storage = storage,
            functions = functions,
            draftDao = { draftDao },
            userRepository = { userRepository },
            artifactDao = { artifactDao },
            database = { database },
            artifactLibraryRepository = { artifactLibraryRepository },
            localDraftManager = localDraftManager,
            moderationRepository = { moderationRepository },
            publishingRepository = { publishingRepository },
            artifactEngagementRepository = { artifactEngagementRepository },
            reflectionPromptManager = { reflectionPromptManager },
            visibilityFilter = { visibilityFilter },
            safetyPolicy = com.saurabh.artifact.domain.SafetyPolicy(),
            diagnosticLogger = diagnosticLogger
        )
    }

    @Test
    fun `uploadArtifactResumable should delegate to PublishingRepository`() = runBlocking {
        val userId = "user123"
        val draft = ArtifactDraftEntity(id = "draft123", userId = TEST_USER_ID, localAudioPath = "/path")
        
        coEvery { publishingRepository.uploadArtifactResumable(userId, draft, any()) } returns Result.success("url123")
        
        val result = repository.uploadArtifactResumable(userId, draft)
        
        assert(result.isSuccess)
        assertEquals("url123", result.getOrThrow())
        coVerify { publishingRepository.uploadArtifactResumable(userId, draft, any()) }
    }

    @Test
    fun `getArtifactsByIds should return ordered list from cache and remote`() = runBlocking {
        val id1 = "id1"
        val id2 = "id2"
        val ids = listOf(id1, id2)
        
        val localEntity = ArtifactEntity(
            id = id1,
            userId = "user1",
            authorName = "Author 1",
            title = "Title 1",
            emotion = Emotion.CALM,
            lastUpdated = System.currentTimeMillis(),
            authorAnonymousId = "",
            authorSigil = "",
            authorSigilSeed = "",
            authorSigilColor = "",
            authorSigilConfigJson = "{}",
            audioUrl = "",
            createdAt = 0,
            durationMs = 0,
            description = "",
            emotionTag = "",
            playCount = 0,
            reactionCount = 0,
            amplitudeData = emptyList()
        )
        
        coEvery { artifactDao.getArtifactsByIds(ids) } returns listOf(localEntity)
        
        // Mock Firestore for id2
        val doc2 = mockk<com.google.firebase.firestore.DocumentSnapshot>(relaxed = true)
        every { doc2.exists() } returns true
        every { doc2.id } returns id2
        every { doc2.toObject(Artifact::class.java) } returns Artifact(id = id2, title = "Title 2")
        
        val collection = mockk<CollectionReference>(relaxed = true)
        every { firestore.collection("artifacts") } returns collection
        
        val docRef2 = mockk<DocumentReference>(relaxed = true)
        every { collection.document(id2) } returns docRef2
        val task2 = mockk<Task<DocumentSnapshot>>(relaxed = true)
        every { docRef2.get() } returns task2
        
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { task2.await() } returns doc2

        val result = repository.getArtifactsByIds(ids)
        
        val list = result.getOrThrow()
        assertEquals(2, list.size)
        assertEquals(id1, list[0].id)
        assertEquals(id2, list[1].id)
        assertEquals("Title 1", list[0].title)
        assertEquals("Title 2", list[1].title)
        
        coVerify { artifactDao.insertAll(any()) }
    }

    @Test
    fun `getArtifactsByIds with empty list should return empty result without querying firestore`() = runBlocking {
        val result = repository.getArtifactsByIds(emptyList())

        Assert.assertTrue(result.isSuccess)
        Assert.assertTrue(result.getOrThrow().isEmpty())
        coVerify(exactly = 0) { artifactDao.getArtifactsByIds(any()) }
        verify(exactly = 0) { firestore.collection(any()) }
    }

    @Test
    fun `getArtifactsByIds on cache miss should fetch individual documents and update cache`() = runBlocking {
        val id1 = "art_miss_1"
        val ids = listOf(id1)

        coEvery { artifactDao.getArtifactsByIds(ids) } returns emptyList()

        val collection = mockk<CollectionReference>(relaxed = true)
        every { firestore.collection("artifacts") } returns collection

        val doc1 = mockk<DocumentSnapshot>(relaxed = true)
        every { doc1.exists() } returns true
        every { doc1.id } returns id1
        every { doc1.toObject(Artifact::class.java) } returns Artifact(id = id1, title = "Miss Title 1")

        val docRef1 = mockk<DocumentReference>(relaxed = true)
        every { collection.document(id1) } returns docRef1
        val task1 = mockk<Task<DocumentSnapshot>>(relaxed = true)
        every { docRef1.get() } returns task1

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { task1.await() } returns doc1

        val result = repository.getArtifactsByIds(ids)

        Assert.assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(1, list.size)
        assertEquals(id1, list[0].id)
        assertEquals("Miss Title 1", list[0].title)

        verify(exactly = 1) { collection.document(id1) }
        coVerify(exactly = 1) { artifactDao.insertAll(any()) }
    }

    @Test
    fun `getArtifactsByIds with multiple missing artifacts should fetch each individually and return ordered results`() = runBlocking {
        val ids = (1..5).map { "art_multi_$it" }

        coEvery { artifactDao.getArtifactsByIds(ids) } returns emptyList()

        val collection = mockk<CollectionReference>(relaxed = true)
        every { firestore.collection("artifacts") } returns collection

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        ids.forEach { id ->
            val doc = mockk<DocumentSnapshot>(relaxed = true)
            every { doc.exists() } returns true
            every { doc.id } returns id
            every { doc.toObject(Artifact::class.java) } returns Artifact(id = id, title = "Title $id")

            val docRef = mockk<DocumentReference>(relaxed = true)
            every { collection.document(id) } returns docRef
            val task = mockk<Task<DocumentSnapshot>>(relaxed = true)
            every { docRef.get() } returns task
            coEvery { task.await() } returns doc
        }

        val result = repository.getArtifactsByIds(ids)

        Assert.assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(5, list.size)
        ids.forEachIndexed { index, id ->
            assertEquals(id, list[index].id)
        }

        ids.forEach { id ->
            verify(exactly = 1) { collection.document(id) }
        }
    }

    @Test
    fun `getArtifactsByIds with mixed cache state should only fetch missing IDs from firestore`() = runBlocking {
        val idA = "idA"
        val idB = "idB"
        val idC = "idC"
        val idD = "idD"
        val ids = listOf(idA, idB, idC, idD)

        val entityA = ArtifactEntity(
            id = idA, userId = "u1", authorName = "A", title = "Title A",
            emotion = Emotion.CALM, lastUpdated = System.currentTimeMillis(),
            authorAnonymousId = "", authorSigil = "", authorSigilSeed = "", authorSigilColor = "",
            authorSigilConfigJson = "{}", audioUrl = "", createdAt = 0, durationMs = 0,
            description = "", emotionTag = "", playCount = 0, reactionCount = 0, amplitudeData = emptyList()
        )
        val entityC = entityA.copy(id = idC, title = "Title C")

        coEvery { artifactDao.getArtifactsByIds(ids) } returns listOf(entityA, entityC)

        val collection = mockk<CollectionReference>(relaxed = true)
        every { firestore.collection("artifacts") } returns collection

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        listOf(idB, idD).forEach { id ->
            val doc = mockk<DocumentSnapshot>(relaxed = true)
            every { doc.exists() } returns true
            every { doc.id } returns id
            every { doc.toObject(Artifact::class.java) } returns Artifact(id = id, title = "Title $id")

            val docRef = mockk<DocumentReference>(relaxed = true)
            every { collection.document(id) } returns docRef
            val task = mockk<Task<DocumentSnapshot>>(relaxed = true)
            every { docRef.get() } returns task
            coEvery { task.await() } returns doc
        }

        val result = repository.getArtifactsByIds(ids)

        Assert.assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(4, list.size)
        assertEquals(listOf(idA, idB, idC, idD), list.map { it.id })

        verify(exactly = 0) { collection.document(idA) }
        verify(exactly = 1) { collection.document(idB) }
        verify(exactly = 0) { collection.document(idC) }
        verify(exactly = 1) { collection.document(idD) }
    }

    @Test
    fun `getArtifactsByIds handling single document fetch failure should return remaining valid items`() = runBlocking {
        val id1 = "art_valid"
        val id2 = "art_failed"
        val ids = listOf(id1, id2)

        coEvery { artifactDao.getArtifactsByIds(ids) } returns emptyList()

        val collection = mockk<CollectionReference>(relaxed = true)
        every { firestore.collection("artifacts") } returns collection

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")

        val doc1 = mockk<DocumentSnapshot>(relaxed = true)
        every { doc1.exists() } returns true
        every { doc1.id } returns id1
        every { doc1.toObject(Artifact::class.java) } returns Artifact(id = id1, title = "Valid Title")

        val docRef1 = mockk<DocumentReference>(relaxed = true)
        every { collection.document(id1) } returns docRef1
        val task1 = mockk<Task<DocumentSnapshot>>(relaxed = true)
        every { docRef1.get() } returns task1
        coEvery { task1.await() } returns doc1

        val docRef2 = mockk<DocumentReference>(relaxed = true)
        every { collection.document(id2) } returns docRef2
        val task2 = mockk<Task<DocumentSnapshot>>(relaxed = true)
        every { docRef2.get() } returns task2
        coEvery { task2.await() } throws RuntimeException("Firestore network error")

        val result = repository.getArtifactsByIds(ids)

        Assert.assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(1, list.size)
        assertEquals(id1, list[0].id)
    }

    @Test
    fun `saveArtifact should delegate to LibraryRepository`() = runBlocking {
        val artifact = Artifact(id = "art123", title = "Test Artifact")
        val userId = "user123"
        val shelf = "Favorites"

        coEvery { artifactLibraryRepository.saveArtifact(userId, artifact, shelf) } returns Result.success(Unit)

        val result = repository.saveArtifact(userId, artifact, shelf)

        assert(result.isSuccess)
        coVerify { artifactLibraryRepository.saveArtifact(userId, artifact, shelf) }
    }

    @Test
    fun `unsaveArtifact should delegate to LibraryRepository`() = runBlocking {
        val artifactId = "art123"
        val userId = "user123"

        coEvery { artifactLibraryRepository.unsaveArtifact(userId, artifactId) } returns Result.success(Unit)

        val result = repository.unsaveArtifact(userId, artifactId)

        assert(result.isSuccess)
        coVerify { artifactLibraryRepository.unsaveArtifact(userId, artifactId) }
    }

    @Test
    fun `saveArtifactToFirestore should succeed on Firestore success`() = runBlocking {
        val userId = "user123"
        val artifactId = "art123"
        val shelf = "Favorites"

        coEvery { artifactLibraryRepository.syncSave(userId, artifactId, shelf) } returns Result.success(Unit)

        val result = repository.saveArtifactToFirestore(userId, artifactId, shelf)

        assert(result.isSuccess)
        coVerify { artifactLibraryRepository.syncSave(userId, artifactId, shelf) }
    }

    @Test
    fun `saveArtifactToFirestore should fail on Firestore failure`() = runBlocking {
        val userId = "user123"
        val artifactId = "art123"
        
        coEvery { artifactLibraryRepository.syncSave(userId, artifactId, any()) } returns Result.failure(Exception("Firestore Error"))

        val result = repository.saveArtifactToFirestore(userId, artifactId)

        assert(result.isFailure)
        assertEquals("Firestore Error", result.exceptionOrNull()?.message)
        coVerify { artifactLibraryRepository.syncSave(userId, artifactId, any()) }
    }

    @Test
    fun `performRemoteDelete should call deleteArtifact callable and succeed when authorized`() = runBlocking {
        val artifactId = "art123"
        val userId = "user123"
        
        every { auth.currentUser?.uid } returns userId
        
        val callable = mockk<HttpsCallableReference>(relaxed = true)
        val callableResult = mockk<HttpsCallableResult>(relaxed = true)
        val callTask = mockk<Task<HttpsCallableResult>>(relaxed = true)
        
        every { functions.getHttpsCallable("deleteArtifact") } returns callable
        every { callable.call(mapOf("artifactId" to artifactId)) } returns callTask
        
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { callTask.await() } returns callableResult

        val result = repository.performRemoteDelete(artifactId)

        assert(result.isSuccess)
        
        // Phase 2 Compliance: Verify NO local cleanup occurs in repository
        coVerify(exactly = 0) { artifactDao.deleteById(any()) }
        coVerify { userRepository.enqueueArtifactCountDecrement(userId, artifactId) }
    }

    @Test
    fun `performRemoteDelete when deleteArtifact returns PERMISSION_DENIED should fail with Unauthorized`() = runBlocking {
        val artifactId = "art123"
        val currentUserId = "user_current"
        
        every { auth.currentUser?.uid } returns currentUserId
        
        val callable = mockk<HttpsCallableReference>(relaxed = true)
        val callTask = mockk<Task<HttpsCallableResult>>(relaxed = true)
        
        every { functions.getHttpsCallable("deleteArtifact") } returns callable
        every { callable.call(mapOf("artifactId" to artifactId)) } returns callTask
        
        val permDeniedException = Exception("PERMISSION_DENIED: Unauthorized: You do not own this reflection")
        
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { callTask.await() } throws permDeniedException

        val result = repository.performRemoteDelete(artifactId)

        assert(result.isFailure)
        assertEquals("Unauthorized: You do not own this reflection", result.exceptionOrNull()?.message)
    }

    @Test
    fun `performRemoteDelete when deleteArtifact returns NOT_FOUND should treat as idempotent success`() = runBlocking {
        val artifactId = "art_legacy_123"
        val currentUserId = "user_owner"
        
        every { auth.currentUser?.uid } returns currentUserId
        
        val callable = mockk<HttpsCallableReference>(relaxed = true)
        val callTask = mockk<Task<HttpsCallableResult>>(relaxed = true)
        
        every { functions.getHttpsCallable("deleteArtifact") } returns callable
        every { callable.call(mapOf("artifactId" to artifactId)) } returns callTask
        
        val notFoundException = Exception("NOT_FOUND: Artifact already deleted")
        
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { callTask.await() } throws notFoundException

        val result = repository.performRemoteDelete(artifactId)

        assert(result.isSuccess)
    }

    @Test
    fun `submitReport should bridge to ModerationRepository and NOT update local cache directly`() = runBlocking {
        val artifactId = "art123"
        val reason = ReportReason.HARASSMENT
        val details = "Some description"
        val deviceId = 456
        
        coEvery { moderationRepository.submitReport(artifactId, reason, details, deviceId) } returns Result.success(Unit)

        val result = repository.submitReport(artifactId, reason, details, deviceId)

        assert(result.isSuccess)
        
        // Verify Bridge
        coVerify { moderationRepository.submitReport(artifactId, reason, details, deviceId) }
        
        // Phase 2 Compliance: Verify NO direct local cache eviction
        coVerify(exactly = 0) { artifactDao.deleteById(any()) }
    }

    @Test
    fun `recordPlay should delegate to EngagementRepository`() = runBlocking {
        coEvery { artifactEngagementRepository.recordPlay(any(), any(), any()) } returns Result.success(Unit)
        repository.recordPlay("user1", "art1", "Joy")
        coVerify { artifactEngagementRepository.recordPlay("user1", "art1", "Joy") }
    }

    @Test
    fun `getSmartReflectionPrompt should delegate to ReflectionPromptManager`() = runBlocking {
        val prompt = ReflectionPrompt(id = "1", question = "Q", category = PromptCategory.GENERAL)
        coEvery { reflectionPromptManager.getSmartReflectionPrompt(any(), any(), any()) } returns prompt
        val result = repository.getSmartReflectionPrompt("Joy", "Ctx", "Time")
        assertEquals(prompt, result)
        coVerify { reflectionPromptManager.getSmartReflectionPrompt("Joy", "Ctx", "Time") }
    }

    @Test
    fun `getArtifactDetail should return artifact detail even if reaction counts fetch fails`() = runBlocking {
        val artifactId = "art123"
        val doc = mockk<com.google.firebase.firestore.DocumentSnapshot>(relaxed = true)
        every { doc.exists() } returns true
        every { doc.id } returns artifactId
        every { doc.get("amplitudeData") } returns listOf(1, 2, 3)
        
        val artifactRef = mockk<DocumentReference>(relaxed = true)
        every { firestore.collection("artifacts").document(artifactId) } returns artifactRef
        
        val getTask = mockk<com.google.android.gms.tasks.Task<com.google.firebase.firestore.DocumentSnapshot>>(relaxed = true)
        every { artifactRef.get() } returns getTask
        
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { getTask.await() } returns doc
        
        // Mock reaction counts failure
        val reactionCountsRef = mockk<DocumentReference>(relaxed = true)
        every { firestore.collection("artifact_reaction_counts").document(artifactId) } returns reactionCountsRef
        
        val reactionCountsTask = mockk<com.google.android.gms.tasks.Task<com.google.firebase.firestore.DocumentSnapshot>>(relaxed = true)
        every { reactionCountsRef.get() } returns reactionCountsTask
        
        coEvery { reactionCountsTask.await() } throws Exception("Offline / Cache Miss")
        
        val result = repository.getArtifactDetail(artifactId)
        
        assert(result.isSuccess)
        val detail = result.getOrThrow()
        assertEquals(artifactId, detail.id)
        assert(detail.reactionCounts != null)
        assertEquals(artifactId, detail.reactionCounts?.artifactId)
        assertEquals(0L, detail.reactionCounts?.totalCount)
        
        verify { diagnosticLogger.warn(DiagnosticCategory.FIRESTORE, "DETAIL_REACTION_COUNTS_CACHE_MISS", any()) }
    }

    @Test
    fun `isCurrentUserAdmin should delegate to ModerationRepository`() = runBlocking {
        coEvery { moderationRepository.isCurrentUserAdmin() } returns true
        
        val result = repository.isCurrentUserAdmin()
        
        assert(result)
        coVerify { moderationRepository.isCurrentUserAdmin() }
    }

    @Test
    fun `getUserArtifactsPage for self should use private registry subcollection exclusively`() = runBlocking {
        val userId = "user_self_123"
        every { auth.currentUser?.uid } returns userId

        val userDoc = mockk<DocumentReference>(relaxed = true)
        every { firestore.collection("users").document(userId) } returns userDoc

        val privateDoc = mockk<DocumentReference>(relaxed = true)
        every { userDoc.collection("private").document("published_artifacts") } returns privateDoc

        val registryCollection = mockk<CollectionReference>(relaxed = true)
        every { privateDoc.collection("artifacts") } returns registryCollection

        val registryQuery = mockk<Query>(relaxed = true)
        every { registryCollection.orderBy("createdAt", Query.Direction.DESCENDING) } returns registryQuery
        every { registryQuery.limit(20) } returns registryQuery

        val regDoc1 = mockk<DocumentSnapshot>(relaxed = true)
        every { regDoc1.id } returns "art_reg_1"

        val regSnapshot = mockk<QuerySnapshot>(relaxed = true)
        every { regSnapshot.isEmpty } returns false
        every { regSnapshot.documents } returns listOf(regDoc1)

        val regTask = mockk<Task<QuerySnapshot>>(relaxed = true)
        every { registryQuery.get() } returns regTask

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { regTask.await() } returns regSnapshot

        val regArtifact = Artifact(id = "art_reg_1", title = "Registry Reflection", userId = userId)
        val entity1 = repository.mapArtifactToEntity(regArtifact)
        coEvery { artifactDao.getArtifactsByIds(listOf("art_reg_1")) } returns listOf(entity1)

        val result = repository.getUserArtifactsPage(userId = userId, isSelf = true)

        assert(result.isSuccess)
        val (list, lastDoc) = result.getOrThrow()
        assertEquals(1, list.size)
        assertEquals("art_reg_1", list[0].id)
        assertEquals(regDoc1, lastDoc)

        // Verify root query was NOT executed
        verify(exactly = 0) { firestore.collection("artifacts").whereEqualTo("userId", any()) }
    }
}
