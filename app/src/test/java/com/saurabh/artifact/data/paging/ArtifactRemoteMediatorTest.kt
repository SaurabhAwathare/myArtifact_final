package com.saurabh.artifact.data.paging

import androidx.paging.*
import androidx.room.withTransaction
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.ArtifactDao
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalPagingApi::class, ExperimentalCoroutinesApi::class)
class ArtifactRemoteMediatorTest {

    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val artifactDao = mockk<ArtifactDao>(relaxed = true)
    private val reportedArtifactDao = mockk<com.saurabh.artifact.data.local.ReportedArtifactDao>(relaxed = true)
    private val safetyPolicy = com.saurabh.artifact.domain.SafetyPolicy()
    private val currentUserId = "test_user"

    private lateinit var mediator: ArtifactRemoteMediator

    @Before
    fun setup() {
        every { database.artifactDao() } returns artifactDao
        every { database.reportedArtifactDao() } returns reportedArtifactDao
        mediator = ArtifactRemoteMediator(
            firestore = firestore,
            database = database,
            currentUserId = currentUserId,
            safetyPolicy = safetyPolicy
        )
    }

    @Test
    fun `load should filter out reported artifacts using local DAO`() = runTest {
        val artifactId = "reported_art"
        coEvery { reportedArtifactDao.getReportedArtifactIds(currentUserId) } returns listOf(artifactId)

        val query = mockk<com.google.firebase.firestore.Query>(relaxed = true)
        val snapshot = mockk<com.google.firebase.firestore.QuerySnapshot>(relaxed = true)
        val doc = mockk<com.google.firebase.firestore.DocumentSnapshot>(relaxed = true)
        
        every { doc.id } returns artifactId
        every { doc.toObject(com.saurabh.artifact.model.Artifact::class.java) } returns com.saurabh.artifact.model.Artifact(id = artifactId)
        every { snapshot.documents } returns listOf(doc)
        
        every { firestore.collection("artifacts") } returns mockk(relaxed = true) {
            every { whereEqualTo(any<String>(), any()) } returns this
            every { orderBy(any<String>(), any()) } returns this
            every { orderBy(any<com.google.firebase.firestore.FieldPath>(), any()) } returns this
            every { limit(any()) } returns query
        }

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        val task = mockk<com.google.android.gms.tasks.Task<com.google.firebase.firestore.QuerySnapshot>>(relaxed = true)
        every { query.get() } returns task
        coEvery { task.await() } returns snapshot

        mockkStatic("androidx.room.RoomDatabaseKt")
        val transactionLambda = slot<suspend () -> Any?>()
        coEvery { database.withTransaction<Any?>(capture(transactionLambda)) } coAnswers {
            transactionLambda.captured.invoke()
        }

        val pagingState = PagingState<Int, com.saurabh.artifact.data.local.ArtifactEntityWithIndex>(
            pages = emptyList(),
            anchorPosition = null,
            config = PagingConfig(pageSize = 20),
            leadingPlaceholderCount = 0
        )

        mediator.load(LoadType.REFRESH, pagingState)

        // Verify that the reported artifact was NOT inserted into artifactDao
        coVerify(exactly = 0) { artifactDao.insertAll(any()) }
        
        unmockkStatic("androidx.room.RoomDatabaseKt")
        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }

    @Test
    fun `initialize returns SKIP_INITIAL_REFRESH when cached data exists`() = runTest {
        coEvery { artifactDao.hasCachedArtifacts() } returns true

        val result = mediator.initialize()

        assertEquals(RemoteMediator.InitializeAction.SKIP_INITIAL_REFRESH, result)
    }

    @Test
    fun `initialize returns LAUNCH_INITIAL_REFRESH when no cached data exists`() = runTest {
        coEvery { artifactDao.hasCachedArtifacts() } returns false

        val result = mediator.initialize()

        assertEquals(RemoteMediator.InitializeAction.LAUNCH_INITIAL_REFRESH, result)
    }

    @Test
    fun `load with REFRESH executes even if initialize returned SKIP_INITIAL_REFRESH`() = runTest {
        // This test ensures that the mediator's load implementation doesn't have 
        // internal state that blocks REFRESH after a SKIP_INITIAL_REFRESH initialization.
        
        coEvery { artifactDao.hasCachedArtifacts() } returns true
        val initResult = mediator.initialize()
        assertEquals(RemoteMediator.InitializeAction.SKIP_INITIAL_REFRESH, initResult)

        // Mock firestore query and success result
        val query = mockk<com.google.firebase.firestore.Query>(relaxed = true)
        val snapshot = mockk<com.google.firebase.firestore.QuerySnapshot>(relaxed = true)
        
        every { firestore.collection("artifacts") } returns mockk(relaxed = true) {
            every { whereEqualTo(any<String>(), any()) } returns this
            every { orderBy(any<String>(), any()) } returns this
            every { orderBy(any<com.google.firebase.firestore.FieldPath>(), any()) } returns this
            every { limit(any()) } returns query
        }
        
        // Mocking the Task and its await()
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        val task = mockk<com.google.android.gms.tasks.Task<com.google.firebase.firestore.QuerySnapshot>>(relaxed = true)
        every { query.get() } returns task
        coEvery { task.await() } returns snapshot
        every { snapshot.documents } returns emptyList()

        // Mock database.withTransaction to just execute the block
        mockkStatic("androidx.room.RoomDatabaseKt")
        val transactionLambda = slot<suspend () -> Any?>()
        coEvery { database.withTransaction<Any?>(capture(transactionLambda)) } coAnswers {
            transactionLambda.captured.invoke()
        }

        val pagingState = PagingState<Int, com.saurabh.artifact.data.local.ArtifactEntityWithIndex>(
            pages = emptyList(),
            anchorPosition = null,
            config = PagingConfig(pageSize = 20),
            leadingPlaceholderCount = 0
        )

        val loadResult = mediator.load(LoadType.REFRESH, pagingState)

        // Verify it reached the network call and returned success
        assert(loadResult is RemoteMediator.MediatorResult.Success)
        verify { firestore.collection("artifacts") }
        
        unmockkStatic("androidx.room.RoomDatabaseKt")
        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }
}
