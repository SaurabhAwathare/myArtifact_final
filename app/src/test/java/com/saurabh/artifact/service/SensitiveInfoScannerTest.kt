package com.saurabh.artifact.service

import com.saurabh.artifact.domain.IdentityScout
import com.saurabh.artifact.model.PiiType
import com.saurabh.artifact.model.TranscriptSegment
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class SensitiveInfoScannerTest {

    private val scout = IdentityScout()
    private val auth = mockk<com.google.firebase.auth.FirebaseAuth>(relaxed = true)
    private val extractor = mockk<EntityExtractorWrapper>(relaxed = true)
    private lateinit var scanner: SensitiveInfoScanner

    @Before
    fun setup() {
        coEvery { extractor.isModelAvailable() } returns false
        scanner = SensitiveInfoScanner(scout, auth, extractor)
    }

    @Test
    fun `test phone number detection`() = runTest {
        val transcript = listOf(
            TranscriptSegment(id = "1", text = "Call me at 123-456-7890", startMs = 0, endMs = 1000, confidence = 1.0f)
        )
        val flagged = scanner.scan(transcript)
        
        assertTrue("Should detect at least one phone flag", flagged.any { it.type == PiiType.PHONE })
    }

    @Test
    fun `test email detection`() = runTest {
        val transcript = listOf(
            TranscriptSegment(id = "1", text = "Email me at test@example.com", startMs = 0, endMs = 1000, confidence = 1.0f)
        )
        val flagged = scanner.scan(transcript)
        
        assertTrue("Should detect at least one email flag", flagged.any { it.type == PiiType.EMAIL })
    }

    @Test
    fun `test name detection through identity scout`() = runTest {
        every { auth.currentUser?.displayName } returns "Saurabh"
        val transcript = listOf(
            TranscriptSegment(id = "1", text = "I am Saurabh", startMs = 0, endMs = 1000, confidence = 1.0f)
        )
        val flagged = scanner.scan(transcript)
        
        assertTrue("Should detect name leak via identity scout", flagged.any { it.type == PiiType.NAME })
    }

    @Test
    fun `test location detection mock`() = runTest {
        val transcript = listOf(
            TranscriptSegment(id = "1", text = "I live on Park Street", startMs = 0, endMs = 1000, confidence = 1.0f)
        )
        val flagged = scanner.scan(transcript)
        
        assertTrue(flagged.any { it.type == PiiType.LOCATION })
    }
}
