package com.saurabh.artifact.service

import com.saurabh.artifact.model.FlaggedSegment
import com.saurabh.artifact.model.PiiType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextScrubberTest {

    private val scanner = mockk<SensitiveInfoScanner>()
    private val scrubber = ContextScrubber(scanner)

    @Test
    fun `scrub replaces detected PII with placeholders`() = runTest {
        val originalText = "My name is John Doe and my phone is 123-456-7890."
        val flagged = listOf(
            FlaggedSegment("1", PiiType.NAME, 0, 0, "John Doe", 1.0f),
            FlaggedSegment("2", PiiType.PHONE, 0, 0, "123-456-7890", 1.0f)
        )
        
        coEvery { scanner.scan(any()) } returns flagged

        val result = scrubber.scrub(originalText)
        
        assertEquals("My name is [NAME] and my phone is [PHONE].", result)
    }

    @Test
    fun `scrub handles large numbers as generic NUMBER placeholder`() = runTest {
        val text = "Meeting at 1234 Elm St in 2026."
        coEvery { scanner.scan(any()) } returns emptyList()

        val result = scrubber.scrub(text)
        
        // 1234 and 2026 should be replaced
        assertEquals("Meeting at [NUMBER] Elm St in [NUMBER].", result)
    }

    @Test
    fun `scrub returns original text when no PII found`() = runTest {
        val text = "A peaceful morning walk."
        coEvery { scanner.scan(any()) } returns emptyList()

        val result = scrubber.scrub(text)
        
        assertEquals(text, result)
    }
}
