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

    @Test
    fun testGenerateSuggestionsForBase_ReturnsRequestedCount() {
        val suggestions = UsernameGenerator.generateSuggestionsForBase("Saurabh", count = 4)
        assertEquals(4, suggestions.size)
    }

    @Test
    fun testGenerateSuggestionsForBase_DoesNotContainRawBaseString() {
        val baseInput = "Saurabh"
        val suggestions = UsernameGenerator.generateSuggestionsForBase(baseInput, count = 4)
        for (suggestion in suggestions) {
            assertFalse(
                "Suggestion '$suggestion' should not contain base input '$baseInput' case-insensitively",
                suggestion.lowercase().contains(baseInput.lowercase())
            )
        }
    }

    @Test
    fun testGenerateSuggestionsForBase_DoesNotAppendNumbersOrSuffixes() {
        val baseInput = "Saurabh"
        val suggestions = UsernameGenerator.generateSuggestionsForBase(baseInput, count = 4)
        for (suggestion in suggestions) {
            assertFalse(
                "Suggestion '$suggestion' should not start with raw base input '$baseInput'",
                suggestion.lowercase().startsWith(baseInput.lowercase())
            )
            assertFalse(
                "Suggestion '$suggestion' should not match raw base concatenation pattern",
                suggestion.matches(Regex("(?i)^saurabh.*"))
            )
        }
    }

    @Test
    fun testGenerateSuggestionsForBase_SatisfiesUsernameConstraints() {
        val suggestions = UsernameGenerator.generateSuggestionsForBase("Saurabh", count = 4)
        for (suggestion in suggestions) {
            assertTrue("Length of '$suggestion' should be >= 3", suggestion.length >= 3)
            assertTrue("Length of '$suggestion' should be <= 30", suggestion.length <= 30)
            assertNull("Username '$suggestion' should be valid", UsernameGenerator.validate(suggestion))
            assertTrue("Username '$suggestion' should pass isValid", UsernameGenerator.isValid(suggestion))
        }
    }

    @Test
    fun testGenerateSuggestionsForBase_ReturnsDistinctSuggestions() {
        val count = 4
        val suggestions = UsernameGenerator.generateSuggestionsForBase("Saurabh", count = count)
        assertEquals("Suggestions should be distinct", suggestions.toSet().size, suggestions.size)
    }

    @Test
    fun testGenerateSuggestionsForBase_HandlesEmptyAndShortInput() {
        val emptyResult = UsernameGenerator.generateSuggestionsForBase("", count = 3)
        assertEquals(3, emptyResult.size)
        for (suggestion in emptyResult) {
            assertNull(UsernameGenerator.validate(suggestion))
        }

        val shortResult = UsernameGenerator.generateSuggestionsForBase("Sa", count = 3)
        assertEquals(3, shortResult.size)
        for (suggestion in shortResult) {
            assertNull(UsernameGenerator.validate(suggestion))
        }
    }
}
