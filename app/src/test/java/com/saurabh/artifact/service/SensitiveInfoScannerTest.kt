package com.saurabh.artifact.service

import com.saurabh.artifact.model.PiiType
import com.saurabh.artifact.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveInfoScannerTest {

    private val scanner = SensitiveInfoScanner()

    @Test
    fun `test phone number detection`() {
        val transcript = listOf(
            TranscriptSegment(id = "1", text = "Call me at 123-456-7890", startMs = 0, endMs = 1000, confidence = 1.0f)
        )
        val flagged = scanner.scan(transcript)
        
        assertEquals(1, flagged.size)
        assertEquals(PiiType.PHONE, flagged[0].type)
        assertEquals("123-456-7890", flagged[0].originalText)
    }

    @Test
    fun `test email detection`() {
        val transcript = listOf(
            TranscriptSegment(id = "1", text = "Email me at test@example.com", startMs = 0, endMs = 1000, confidence = 1.0f)
        )
        val flagged = scanner.scan(transcript)
        
        assertEquals(1, flagged.size)
        assertEquals(PiiType.EMAIL, flagged[0].type)
        assertEquals("test@example.com", flagged[0].originalText)
    }

    @Test
    fun `test name detection mock`() {
        val transcript = listOf(
            TranscriptSegment(id = "1", text = "I am Rahul", startMs = 0, endMs = 1000, confidence = 1.0f)
        )
        val flagged = scanner.scan(transcript)
        
        assertTrue(flagged.any { it.type == PiiType.NAME && it.originalText == "Rahul" })
    }

    @Test
    fun `test location detection mock`() {
        val transcript = listOf(
            TranscriptSegment(id = "1", text = "I live on Park Street", startMs = 0, endMs = 1000, confidence = 1.0f)
        )
        val flagged = scanner.scan(transcript)
        
        assertTrue(flagged.any { it.type == PiiType.LOCATION })
    }
}
