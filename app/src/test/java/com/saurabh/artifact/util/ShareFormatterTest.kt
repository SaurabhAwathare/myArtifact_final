package com.saurabh.artifact.util

import com.saurabh.artifact.model.SharePayload
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareFormatterTest {

    @Test
    fun `formatShareText without shareUrl`() {
        val payload = SharePayload(
            artifactId = "id123",
            title = "Midnight Rain",
            authorName = "Rainy Day"
        )
        val expected = """
            Listen to this Artifact on Artifact

            "Midnight Rain"

            by Rainy Day
        """.trimIndent()
        
        assertEquals(expected, ShareFormatter.formatShareText(payload).trim())
    }

    @Test
    fun `formatShareText with authorSigil`() {
        val payload = SharePayload(
            artifactId = "id123",
            title = "Midnight Rain",
            authorName = "Rainy Day",
            authorSigil = "A1"
        )
        val expected = """
            Listen to this Artifact on Artifact

            "Midnight Rain"

            by Rainy Day A1
        """.trimIndent()
        
        assertEquals(expected, ShareFormatter.formatShareText(payload).trim())
    }

    @Test
    fun `formatShareText with shareUrl`() {
        val payload = SharePayload(
            artifactId = "id123",
            title = "Midnight Rain",
            authorName = "Rainy Day",
            shareUrl = "https://artifact.com/id123"
        )
        val expected = """
            Listen to this Artifact on Artifact

            "Midnight Rain"

            by Rainy Day

            https://artifact.com/id123
        """.trimIndent()
        
        assertEquals(expected, ShareFormatter.formatShareText(payload).trim())
    }
}
