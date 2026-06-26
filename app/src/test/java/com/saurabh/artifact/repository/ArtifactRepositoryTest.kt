package com.saurabh.artifact.repository

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.ArtifactDao
import com.saurabh.artifact.data.local.ArtifactEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.service.PersonalizationEngine
import com.saurabh.artifact.service.ReflectionAIService
import com.saurabh.artifact.util.ArtifactLogger
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
    private val aiService = mockk<ReflectionAIService>(relaxed = true)
    private val personalizationEngine = mockk<PersonalizationEngine>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val artifactDao = mockk<ArtifactDao>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val pendingInteractionDao = mockk<PendingInteractionDao>(relaxed = true)

    private lateinit var repository: ArtifactRepository

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        mockkObject(ArtifactLogger)
        
        repository = ArtifactRepository(
            context = context,
            auth = auth,
            firestore = firestore,
            storage = storage,
            draftDao = draftDao,
            userRepository = { userRepository },
            aiService = { aiService },
            personalizationEngine = { personalizationEngine },
            settingsRepository = { settingsRepository },
            artifactDao = artifactDao,
            database = database,
            pendingInteractionDao = pendingInteractionDao
        )
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
            authorAvatarSeed = "",
            authorAvatarColor = "",
            authorAvatarConfigJson = "{}",
            audioUrl = "",
            createdAt = 0,
            durationMs = 0,
            description = "",
            emotionTag = "",
            playCount = 0,
            reactionCount = 0,
            commentCount = 0,
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
}
