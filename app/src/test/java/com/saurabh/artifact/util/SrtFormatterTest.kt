package com.saurabh.artifact.util

import com.saurabh.artifact.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class SrtFormatterTest {

    @Test
    fun `format generates valid SRT string`() {
        val segments = listOf(
            TranscriptSegment("1", "Hello world", 0, 1000, 1.0f),
            TranscriptSegment("2", "Test subtitle", 1500, 3000, 1.0f)
        )
        
        val expected = """
            1
            00:00:00,000 --> 00:00:01,000
            Hello world

            2
            00:00:01,500 --> 00:00:03,000
            Test subtitle

        """.trimIndent() + "\n"
        
        val result = SrtFormatter.format(segments)
        assertEquals(expected, result)
    }

    @Test
    fun `segmentLine splits long text correctly`() {
        val text = "This is a very long text that should be split into multiple lines for better readability"
        val result = SrtFormatter.segmentLine(text, 20)
        
        // "This is a very long" (18 chars)
        // "text that should be" (19 chars)
        // "split into multiple" (19 chars)
        // "lines for better" (16 chars)
        // "readability" (11 chars)
        
        assertEquals(5, result.size)
        assertEquals("This is a very long", result[0])
    }
}
