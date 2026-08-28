package com.saurabh.artifact.util

import org.junit.Assert.assertEquals
import org.junit.Test

class QualitativeLanguageTest {

    @Test
    fun `test resonance label boundaries`() {
        assertEquals("no resonance yet", QualitativeLanguage.getResonanceLabel(0))
        assertEquals("a single soul", QualitativeLanguage.getResonanceLabel(1))
        assertEquals("a few souls", QualitativeLanguage.getResonanceLabel(2))
        assertEquals("a few souls", QualitativeLanguage.getResonanceLabel(5))
        assertEquals("many souls", QualitativeLanguage.getResonanceLabel(6))
        assertEquals("many souls", QualitativeLanguage.getResonanceLabel(20))
        assertEquals("a vast circle", QualitativeLanguage.getResonanceLabel(21))
        assertEquals("a vast circle", QualitativeLanguage.getResonanceLabel(100))
        assertEquals("a boundless echo", QualitativeLanguage.getResonanceLabel(101))
        assertEquals("a boundless echo", QualitativeLanguage.getResonanceLabel(1000))
    }

    @Test
    fun `test negative counts return zero state`() {
        assertEquals("no resonance yet", QualitativeLanguage.getResonanceLabel(-1))
    }
}
