package com.saurabh.artifact.util

import org.junit.Assert.*
import org.junit.Test

class SecureStringTest {

    @Test
    fun `test clear() zeros out the underlying array`() {
        val original = "sensitivePassword"
        val secureString = SecureString.fromString(original)
        
        assertEquals(original, secureString.toUnsecureString())
        
        secureString.clear()
        
        // After clearing, the content should be null characters
        val cleared = secureString.toUnsecureString()
        assertTrue(cleared.all { it == '\u0000' })
        assertEquals(original.length, cleared.length)
    }

    @Test
    fun `test toUnsecureString() creates a new string`() {
        val original = "secret"
        val secureString = SecureString.fromString(original)
        val unsecure = secureString.toUnsecureString()
        
        assertEquals(original, unsecure)
        assertNotSame(original, unsecure) // Should be a different instance if fromCharArray was used
    }

    @Test
    fun `test subSequence returns new SecureString`() {
        val secureString = SecureString.fromString("0123456789")
        val sub = secureString.subSequence(2, 5) as SecureString
        
        assertEquals("234", sub.toUnsecureString())
        
        secureString.clear()
        // Subsequence should be independent
        assertEquals("234", sub.toUnsecureString())
    }

    @Test
    fun `test serialization`() {
        val original = "secretData"
        val secureString = SecureString.fromString(original)
        val json = kotlinx.serialization.json.Json.encodeToString(SecureString.serializer(), secureString)
        
        assertEquals("\"secretData\"", json)
        
        val deserialized = kotlinx.serialization.json.Json.decodeFromString(SecureString.serializer(), json)
        assertEquals(original, deserialized.toUnsecureString())
    }
}
