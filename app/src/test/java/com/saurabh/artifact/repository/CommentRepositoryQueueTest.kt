package com.saurabh.artifact.repository

import android.content.Context
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.model.*
import com.saurabh.artifact.service.ModerationService
import com.saurabh.artifact.worker.InteractionSyncWorker
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.BitSet

@OptIn(ExperimentalCoroutinesApi::class)
class CommentRepositoryQueueTest {

    private val context = mockk<Context>(relaxed = true)
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val moderationService = mockk<ModerationService>(relaxed = true)
    private val pendingInteractionDao = mockk<PendingInteractionDao>(relaxed = true)
    private val gson = Gson()

    private lateinit var repository: CommentRepository

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        
        mockkObject(InteractionSyncWorker.Companion)
        every { InteractionSyncWorker.enqueue(any()) } just Runs

        repository = CommentRepository(
            context, firestore, moderationService, pendingInteractionDao, gson
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `submitReflection should enqueue interaction and trigger worker`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val content = "Test reflection content"
        
        // Mock Firestore for artifact owner lookup
        val artifactDoc = mockk<DocumentSnapshot>()
        every { artifactDoc.getString("userId") } returns "owner456"
        
        val artifactRef = mockk<DocumentReference>()
        every { firestore.collection("artifacts").document(artifactId) } returns artifactRef
        
        // Use coEvery for await()
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        coEvery { artifactRef.get().await() } returns artifactDoc

        val result = repository.submitReflection(artifactId, userId, content)
        
        assert(result.isSuccess)
        
        coVerify { pendingInteractionDao.insert(any()) }
        verify { InteractionSyncWorker.enqueue(context) }
    }

    @Test
    fun `reactToComment should enqueue interaction and trigger worker`() = runTest {
        val commentId = "comment123"
        val reactionType = ReactionType.I_HEAR_YOU
        
        mockkStatic(com.google.firebase.auth.FirebaseAuth::class)
        val auth = mockk<com.google.firebase.auth.FirebaseAuth>()
        val firebaseUser = mockk<com.google.firebase.auth.FirebaseUser>()
        every { com.google.firebase.auth.FirebaseAuth.getInstance() } returns auth
        every { auth.currentUser } returns firebaseUser
        every { firebaseUser.uid } returns "user123"

        val result = repository.reactToComment(commentId, reactionType)
        
        assert(result.isSuccess)
        
        coVerify { pendingInteractionDao.insert(any()) }
        coVerify { pendingInteractionDao.deleteByType(commentId, "user123", any()) }
        verify { InteractionSyncWorker.enqueue(context) }
    }
}
