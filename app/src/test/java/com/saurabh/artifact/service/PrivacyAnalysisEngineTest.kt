package com.saurabh.artifact.service

import com.saurabh.artifact.domain.IdentityScout
import com.saurabh.artifact.model.PiiType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyAnalysisEngineTest {

    private val scout = IdentityScout()
    private val auth = mockk<com.google.firebase.auth.FirebaseAuth>(relaxed = true)
    private val scanner = SensitiveInfoScanner(scout, auth)
    private val engine = PrivacyAnalysisEngine(scanner)

    @Test
    fun `analyze correctly identifies PII using scanner`() = runBlocking {
        every { auth.currentUser?.displayName } returns "Rahul"
        // The default fallback transcript contains "Rahul"
        val result = engine.analyze("dummy_path")
        
        assertEquals(SafetyLevel.MEDIUM, result.safetyLevel)
        assertTrue(result.detectedRisks.any { it.contains("NAME") && it.contains("real name") })
    }

    @Test
    fun `analyze returns safe level for clean transcript`() = runBlocking {
        // This relies on the current behavior where providing a transcriptPath that doesn't exist 
        // will result in an empty string transcript if transcriptPath is provided.
        // But wait, the current implementation of analyze defaults to the mock transcript if transcriptPath is null.
        // Let's test with an empty file.
        
        val result = engine.analyze("dummy_path", "non_existent_file.txt")
        
        assertEquals(SafetyLevel.LOW, result.safetyLevel)
        assertTrue(result.detectedRisks.isEmpty())
    }
}
