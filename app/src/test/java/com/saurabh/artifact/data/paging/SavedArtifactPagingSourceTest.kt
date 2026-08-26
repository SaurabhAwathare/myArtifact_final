package com.saurabh.artifact.data.paging

import androidx.paging.PagingSource
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.saurabh.artifact.domain.SafetyPolicy
import com.saurabh.artifact.domain.ArtifactVisibilityFilter
import com.saurabh.artifact.model.Artifact
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedArtifactPagingSourceTest {

    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val safetyPolicy = SafetyPolicy()
    private val visibilityFilter = mockk<ArtifactVisibilityFilter>(relaxed = true)
    private val userId = "test_user"

    private lateinit var pagingSource: SavedArtifactPagingSource

    @Before
    fun setup() {
        pagingSource = SavedArtifactPagingSource(
            firestore = firestore,
            userId = userId,
            safetyPolicy = safetyPolicy,
            visibilityFilter = visibilityFilter
        )
    }

    @Test
    fun `load should return artifacts and apply safety filtering`() = runTest {
        val artifactId = "art1"
        val savedIdDoc = mockk<com.google.firebase.firestore.DocumentSnapshot>(relaxed = true)
        val artifactDoc = mockk<com.google.firebase.firestore.DocumentSnapshot>(relaxed = true)
        val idSnapshot = mockk<QuerySnapshot>(relaxed = true)
        val artSnapshot = mockk<QuerySnapshot>(relaxed = true)

        every { savedIdDoc.id } returns artifactId
        every { idSnapshot.documents } returns listOf(savedIdDoc)
        every { idSnapshot.isEmpty } returns false
        every { idSnapshot.size() } returns 1

        every { artifactDoc.id } returns artifactId
        every { artifactDoc.toObject(Artifact::class.java) } returns Artifact(id = artifactId)
        every { artSnapshot.documents } returns listOf(artifactDoc)

        // Mock Firestore calls
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        
        val idQuery = mockk<Query>(relaxed = true)
        every { firestore.collection("users").document(userId).collection("savedArtifacts").orderBy(any<String>(), any()).limit(any()) } returns idQuery
        val idTask = mockk<Task<QuerySnapshot>>(relaxed = true)
        every { idQuery.get() } returns idTask
        coEvery { idTask.await() } returns idSnapshot

        val artQuery = mockk<Query>(relaxed = true)
        every { firestore.collection("artifacts").whereIn(any<com.google.firebase.firestore.FieldPath>(), any()) } returns artQuery
        val artTask = mockk<Task<QuerySnapshot>>(relaxed = true)
        every { artQuery.get() } returns artTask
        coEvery { artTask.await() } returns artSnapshot

        // Mock suppression
        coEvery { visibilityFilter.getSuppressedIdsSnapshot(userId) } returns setOf(artifactId)

        val params = PagingSource.LoadParams.Refresh<com.google.firebase.firestore.DocumentSnapshot>(
            key = null,
            loadSize = 10,
            placeholdersEnabled = false
        )

        val result = pagingSource.load(params)

        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        // Should be empty because artifactId is suppressed
        assertTrue(page.data.isEmpty())
        
        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
    }
}
