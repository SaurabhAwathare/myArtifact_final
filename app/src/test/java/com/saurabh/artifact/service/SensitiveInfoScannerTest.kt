package com.saurabh.artifact.service

import com.saurabh.artifact.domain.IdentityScout
import com.saurabh.artifact.model.PiiType
import com.saurabh.artifact.model.TranscriptSegment
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveInfoScannerTest {

    private val scout = IdentityScout()
    private val auth = mockk<com.google.firebase.auth.FirebaseAuth>(relaxed = true)
    private val scanner = SensitiveInfoScanner(scout, auth)

    @Test
    fun `test phone number detection`() {
        val transcript = listOf(
            TranscriptSegment(id = "1", text = "Call me at 123-456-7890", startMs = 0, endMs = 1000, confidence = 1.0f)
        )
        val flagged = scanner.scan(transcript)
        
        // phoneRegex should match "123-456-7890" -> 1 flag
        // identityScout.detectLeaks might also match if it detects behavioral patterns or digits
        // Phone number detection in IdentityScout (7+ digits) will flag this as well.
        // distinctBy originalText + startMs might merge them if they have same text
        
        assertTrue("Should detect at least one phone flag", flagged.any { it.type == PiiType.PHONE })
    }

    @Test
    fun `test email detection`() {
        val transcript = listOf(
            TranscriptSegment(id = "1", text = "Email me at test@example.com", startMs = 0, endMs = 1000, confidence = 1.0f)
        )
        val flagged = scanner.scan(transcript)
        
        assertTrue("Should detect at least one email flag", flagged.any { it.type == PiiType.EMAIL })
    }

    @Test
    fun `test name detection through identity scout`() {
        every { auth.currentUser?.displayName } returns "Saurabh"
        val transcript = listOf(
            TranscriptSegment(id = "1", text = "I am Saurabh", startMs = 0, endMs = 1000, confidence = 1.0f)
        )
        val flagged = scanner.scan(transcript)
        
        // "Saurabh" vs "Saurabh" -> Exact match -> REAL_NAME -> PiiType.NAME
        
        assertTrue("Should detect name leak via identity scout", flagged.any { it.type == PiiType.NAME })
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
