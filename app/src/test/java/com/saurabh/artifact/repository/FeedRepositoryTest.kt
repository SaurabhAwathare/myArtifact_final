package com.saurabh.artifact.repository

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.domain.ArtifactVisibilityFilter
import com.saurabh.artifact.domain.SafetyPolicy
import com.saurabh.artifact.service.RecommendationService
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedRepositoryTest {

    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
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
            recommendationService = recommendationService,
            visibilityFilter = visibilityFilter,
            safetyPolicy = safetyPolicy,
            diagnosticLogger = diagnosticLogger
        )
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
}
