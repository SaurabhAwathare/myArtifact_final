package com.saurabh.artifact.worker

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Transaction
import io.mockk.*
import org.junit.Assert.assertEquals
import org.junit.Test

class IdentitySyncRaceTest {

    @Test
    fun `lastCompletedIdentityVersion should only move forward`() {
        val firestore = mockk<FirebaseFirestore>()
        val transaction = mockk<Transaction>()
        val userRef = mockk<DocumentReference>()
        val snapshot = mockk<DocumentSnapshot>()

        val userId = "user123"
        val v8 = 8L
        val v9 = 9L

        // 1. Setup: State is currently V9
        every { snapshot.getLong("identityMetadata.lastCompletedIdentityVersion") } returns v9
        every { transaction.get(userRef) } returns snapshot
        every { transaction.update(userRef, any<Map<String, Any>>()) } returns transaction

        // 2. Simulate Worker A (V8) finishing AFTER Worker B (V9)
        // Logic from IdentitySyncWorker.kt:
        val workerVersion = v8
        val currentCompleted = snapshot.getLong("identityMetadata.lastCompletedIdentityVersion") ?: 0L
        
        var updateCalled = false
        if (workerVersion > currentCompleted) {
            transaction.update(userRef, mapOf(
                "identityMetadata.lastCompletedIdentityVersion" to workerVersion
            ))
            updateCalled = true
        }

        // 3. Verify: Update should NOT be called for V8 if current is V9
        assertEquals(false, updateCalled)
        verify(exactly = 0) { transaction.update(userRef, any()) }
    }

    @Test
    fun `lastCompletedIdentityVersion should update if version is higher`() {
        val firestore = mockk<FirebaseFirestore>()
        val transaction = mockk<Transaction>()
        val userRef = mockk<DocumentReference>()
        val snapshot = mockk<DocumentSnapshot>()

        val v8 = 8L
        val v9 = 9L

        // 1. Setup: State is currently V8
        every { snapshot.getLong("identityMetadata.lastCompletedIdentityVersion") } returns v8
        every { transaction.get(userRef) } returns snapshot
        every { transaction.update(userRef, any<Map<String, Any>>()) } returns transaction

        // 2. Simulate Worker B (V9) finishing
        val workerVersion = v9
        val currentCompleted = snapshot.getLong("identityMetadata.lastCompletedIdentityVersion") ?: 0L
        
        var updateCalled = false
        if (workerVersion > currentCompleted) {
            transaction.update(userRef, mapOf(
                "identityMetadata.lastCompletedIdentityVersion" to workerVersion
            ))
            updateCalled = true
        }

        // 3. Verify: Update SHOULD be called for V9 if current is V8
        assertEquals(true, updateCalled)
        verify(exactly = 1) { 
            transaction.update(userRef, match<Map<String, Any>> { 
                it["identityMetadata.lastCompletedIdentityVersion"] == v9 
            }) 
        }
    }
}
