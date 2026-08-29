package com.saurabh.artifact.repository

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.model.CommunityAtmosphere
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityRepositoryTest {

    private val firestore = mockk<FirebaseFirestore>()
    private val repository = CommunityRepository(firestore)

    @Test
    fun `getLatestAtmosphere returns object on success`() = runTest {
        val mockDoc = mockk<DocumentSnapshot>()
        val mockAtmosphere = CommunityAtmosphere(totalArtifacts = 42L, status = "ACTIVE")
        
        every { mockDoc.exists() } returns true
        every { mockDoc.toObject(CommunityAtmosphere::class.java) } returns mockAtmosphere
        
        val mockRef = mockk<DocumentReference>()
        every { firestore.collection("community").document("atmosphere") } returns mockRef
        every { mockRef.get() } returns Tasks.forResult(mockDoc)
        
        val result = repository.getLatestAtmosphere()
        
        assertTrue(result.isSuccess)
        assertEquals(42L, result.getOrNull()?.totalArtifacts)
        assertEquals("ACTIVE", result.getOrNull()?.status)
    }

    @Test
    fun `getLatestAtmosphere returns insufficient data when document missing`() = runTest {
        val mockDoc = mockk<DocumentSnapshot>()
        every { mockDoc.exists() } returns false
        every { mockDoc.toObject(CommunityAtmosphere::class.java) } returns null
        
        val mockRef = mockk<DocumentReference>()
        every { firestore.collection("community").document("atmosphere") } returns mockRef
        every { mockRef.get() } returns Tasks.forResult(mockDoc)
        
        val result = repository.getLatestAtmosphere()
        
        assertTrue(result.isSuccess)
        assertEquals("INSUFFICIENT_DATA", result.getOrNull()?.status)
    }
}
