package com.saurabh.artifact.domain

import com.saurabh.artifact.data.local.ReportedArtifactDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ArtifactVisibilityFilterTest {

    private val reportedArtifactDao = mockk<ReportedArtifactDao>(relaxed = true)
    private val firestore = mockk<com.google.firebase.firestore.FirebaseFirestore>(relaxed = true)
    private lateinit var filter: ArtifactVisibilityFilter

    @Before
    fun setup() {
        filter = ArtifactVisibilityFilter(reportedArtifactDao, firestore)
    }

    @Test
    fun `getSuppressedIdsSnapshot should return set of IDs from DAO`() = runBlocking {
        val userId = "user1"
        val reportedIds = listOf("art1", "art2")
        coEvery { reportedArtifactDao.getReportedArtifactIds(userId) } returns reportedIds

        val result = filter.getSuppressedIdsSnapshot(userId)

        assertEquals(setOf("art1", "art2"), result)
    }

    @Test
    fun `observeSuppressedIds should emit set of IDs from DAO flow`() = runBlocking {
        val userId = "user1"
        val reportedIds = listOf("art1", "art2")
        every { reportedArtifactDao.observeReportedArtifactIds(userId) } returns flowOf(reportedIds)

        val result = filter.observeSuppressedIds(userId).first()

        assertEquals(setOf("art1", "art2"), result)
    }

    @Test
    fun `syncReportsFromRemote should insert reported artifacts into DAO`() = runBlocking {
        val userId = "user1"
        val artifactId = "art123"
        val scope = this
        
        val collectionRef = mockk<com.google.firebase.firestore.CollectionReference>(relaxed = true)
        val snapshot = mockk<com.google.firebase.firestore.QuerySnapshot>(relaxed = true)
        val change = mockk<com.google.firebase.firestore.DocumentChange>(relaxed = true)
        val doc = mockk<com.google.firebase.firestore.QueryDocumentSnapshot>(relaxed = true)

        every { firestore.collection("users").document(userId).collection("private").document("reports").collection("artifacts") } returns collectionRef
        
        val listenerSlot = slot<com.google.firebase.firestore.EventListener<com.google.firebase.firestore.QuerySnapshot>>()
        every { collectionRef.addSnapshotListener(capture(listenerSlot)) } returns mockk(relaxed = true)

        every { change.type } returns com.google.firebase.firestore.DocumentChange.Type.ADDED
        every { change.document } returns doc
        every { doc.id } returns artifactId
        every { snapshot.documentChanges } returns listOf(change)

        filter.syncReportsFromRemote(userId, scope).first() // Trigger subscription

        // Simulate snapshot update
        listenerSlot.captured.onEvent(snapshot, null)

        io.mockk.coVerify { reportedArtifactDao.insert(any()) }
    }
}
