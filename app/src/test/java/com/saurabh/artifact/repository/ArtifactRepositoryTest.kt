package com.saurabh.artifact.repository

import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ArtifactRepositoryTest {
    private val context = mockk<Context>(relaxed = true)
    private val auth = mockk<FirebaseAuth>(relaxed = true)
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val storage = mockk<FirebaseStorage>(relaxed = true)
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
    private val pendingInteractionDao = mockk<PendingInteractionDao>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var repository: ArtifactRepository

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
            draftDao = { draftDao },
            userRepository = { userRepository },
            artifactDao = { artifactDao },
            database = { database },
            artifactLibraryRepository = { artifactLibraryRepository },
            moderationRepository = { moderationRepository },
            publishingRepository = { publishingRepository },
            artifactEngagementRepository = { artifactEngagementRepository },
            reflectionPromptManager = { reflectionPromptManager },
            diagnosticLogger = diagnosticLogger
        )
    }

    @Test
    fun `uploadArtifactResumable should delegate to PublishingRepository`() = runBlocking {
        val userId = "user123"
        val draft = ArtifactDraftEntity(id = "draft123", localAudioPath = "/path")
        
        coEvery { publishingRepository.uploadArtifactResumable(userId, draft, any()) } returns Result.success("url123")
        
        val result = repository.uploadArtifactResumable(userId, draft)
        
        assert(result.isSuccess)
        assertEquals("url123", result.getOrThrow())
        coVerify { publishingRepository.uploadArtifactResumable(userId, draft, any()) }
    }

    @Test
    fun `createArtifactDocument should delegate to PublishingRepository`() = runBlocking {
        val userId = "user123"
        val draft = ArtifactDraftEntity(id = "draft123", localAudioPath = "/path")
        val author = AuthorSnapshot(name = "Author")
        
        coEvery { publishingRepository.createArtifactDocument(userId, author, "url", draft) } returns Result.success("id123")
        
        val result = repository.createArtifactDocument(userId, author, "url", draft)
        
        assert(result.isSuccess)
        assertEquals("id123", result.getOrThrow())
        coVerify { publishingRepository.createArtifactDocument(userId, author, "url", draft) }
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
        
        val querySnapshot = mockk<com.google.firebase.firestore.QuerySnapshot>(relaxed = true)
        every { querySnapshot.documents } returns listOf(doc2)
        
        val collection = mockk<com.google.firebase.firestore.CollectionReference>(relaxed = true)
        every { firestore.collection("artifacts") } returns collection
        val query = mockk<com.google.firebase.firestore.Query>(relaxed = true)
        every { collection.whereIn(com.google.firebase.firestore.FieldPath.documentId(), any()) } returns query
        
        val task = mockk<com.google.android.gms.tasks.Task<com.google.firebase.firestore.QuerySnapshot>>(relaxed = true)
        every { query.get() } returns task
        
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { task.await() } returns querySnapshot

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
    fun `performRemoteDelete should perform soft delete by setting status to DELETED`() = runBlocking {
        val artifactId = "art123"
        val userId = "user123"
        
        every { auth.currentUser?.uid } returns userId
        
        val docRef = mockk<DocumentReference>(relaxed = true)
        every { firestore.collection("artifacts").document(artifactId) } returns docRef
        
        val snapshot = mockk<com.google.firebase.firestore.DocumentSnapshot>(relaxed = true)
        every { snapshot.exists() } returns true
        every { snapshot.getString("userId") } returns userId
        
        val getTask = mockk<com.google.android.gms.tasks.Task<com.google.firebase.firestore.DocumentSnapshot>>(relaxed = true)
        every { docRef.get() } returns getTask
        
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { getTask.await() } returns snapshot

        // Mock Bridge
        coEvery { moderationRepository.isCurrentUserAdmin() } returns false
        coEvery { moderationRepository.softDeleteArtifact(artifactId) } returns Result.success(Unit)

        val result = repository.performRemoteDelete(artifactId)

        assert(result.isSuccess)
        
        // Verify bridge calls
        coVerify { moderationRepository.softDeleteArtifact(artifactId) }
        
        // Phase 2 Compliance: Verify NO local cleanup occurs in repository
        coVerify(exactly = 0) { artifactDao.deleteById(any()) }
        coVerify { userRepository.decrementArtifactsCount(userId) }
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
}
