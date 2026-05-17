package com.saurabh.artifact.service

import com.saurabh.artifact.model.PiiType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyAnalysisEngineTest {

    private val scanner = SensitiveInfoScanner()
    private val engine = PrivacyAnalysisEngine(scanner)

    @Test
    fun `analyze correctly identifies PII using scanner`() = runBlocking {
        // We use the default fallback transcript which contains "Rahul" and "Pune"
        val result = engine.analyze("dummy_path")
        
        assertEquals(SafetyLevel.MEDIUM, result.safetyLevel)
        assertTrue(result.detectedRisks.any { it.contains("NAME") && it.contains("Rahul") })
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
