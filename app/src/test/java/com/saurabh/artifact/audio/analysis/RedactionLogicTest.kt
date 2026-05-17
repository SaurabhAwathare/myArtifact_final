package com.saurabh.artifact.audio.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionLogicTest {

    @Test
    fun `scanForSensitiveInfo detects phone numbers and emails`() {
        val text = "Call me at 123-456-7890 or email me at test@example.com"
        val results = RedactionLogic.scanForSensitiveInfo(text)
        
        assertEquals(2, results.size)
        assertTrue(results.any { it.type == "PHONE_NUMBER" })
        assertTrue(results.any { it.type == "EMAIL" })
    }

    @Test
    fun `redactText replaces info with markers`() {
        val text = "Contact 123-456-7890"
        val info = RedactionLogic.scanForSensitiveInfo(text)
        val result = RedactionLogic.redactText(text, info)
        
        assertEquals("Contact [REDACTED PHONE_NUMBER]", result)
    }
}
