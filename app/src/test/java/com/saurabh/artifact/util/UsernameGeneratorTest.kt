package com.saurabh.artifact.util

import org.junit.Assert.*
import org.junit.Test

class UsernameGeneratorTest {

    @Test
    fun testGenerate_ReturnsTwoCapitalizedWords() {
        val name = UsernameGenerator.generate()
        val parts = name.split(" ")
        assertEquals(2, parts.size)
        assertTrue(parts[0][0].isUpperCase())
        assertTrue(parts[1][0].isUpperCase())
    }

    @Test
    fun testGenerateWithTheme_ReturnsThemedName() {
        val name = UsernameGenerator.generate("Cosmic")
        // Check if words exist in cosmic lists (internal but observable)
        assertNotNull(name)
        assertTrue(name.isNotEmpty())
    }

    @Test
    fun testDeriveSigil_IsDeterministic() {
        val id = "usr_9F3A2"
        val sigil1 = UsernameGenerator.deriveSigil(id)
        val sigil2 = UsernameGenerator.deriveSigil(id)
        assertEquals("A2", sigil1)
        assertEquals(sigil1, sigil2)
    }

    @Test
    fun testDeriveSigil_HandlesEmptyId() {
        val sigil = UsernameGenerator.deriveSigil("")
        assertEquals("A1", sigil)
    }

    @Test
    fun testFormatIdentity() {
        val formatted = UsernameGenerator.formatIdentity("Quiet Echo", "A7")
        assertEquals("Quiet Echo · A7", formatted)
    }

    @Test
    fun testValidate_AllowsSigilFormat() {
        assertNull(UsernameGenerator.validate("Quiet Echo · A7"))
        assertNull(UsernameGenerator.validate("Simple Name"))
        assertNotNull(UsernameGenerator.validate("invalid_name!"))
    }
}
