package com.saurabh.artifact.nlp

import com.saurabh.artifact.model.Emotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotionAnalyzerTest {

    private val analyzer = EmotionAnalyzer()

    @Test
    fun `detects Happy emotion correctly`() {
        val result = analyzer.analyze("I am so happy and feeling great today!")
        assertEquals(Emotion.HAPPY, result.emotion)
        assertTrue(result.confidence > 0.5f)
    }

    @Test
    fun `detects Sad emotion correctly`() {
        val result = analyzer.analyze("Feeling very sad and unhappy about this.")
        assertEquals(Emotion.SAD, result.emotion)
        assertTrue(result.confidence > 0.5f)
    }

    @Test
    fun `detects Lonely emotion correctly`() {
        val result = analyzer.analyze("I feel so alone and isolated lately.")
        assertEquals(Emotion.LONELY, result.emotion)
    }

    @Test
    fun `detects Anxious emotion correctly`() {
        val result = analyzer.analyze("I'm so worried and stressed about the future.")
        assertEquals(Emotion.ANXIOUS, result.emotion)
    }

    @Test
    fun `detects Motivated emotion correctly`() {
        val result = analyzer.analyze("I'm inspired and ready to achieve my goals!")
        assertEquals(Emotion.MOTIVATED, result.emotion)
    }

    @Test
    fun `returns Neutral for empty text`() {
        val result = analyzer.analyze("")
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0f, result.confidence)
    }

    @Test
    fun `returns Neutral for unknown text`() {
        val result = analyzer.analyze("The sky is blue and the grass is green.")
        assertEquals(Emotion.NEUTRAL, result.emotion)
    }

    @Test
    fun `handles mixed emotions by picking top score`() {
        // "happy" (Happy) vs "sad", "hurt" (Sad)
        val result = analyzer.analyze("I'm happy but also feeling a bit sad and hurt.")
        assertEquals(Emotion.SAD, result.emotion)
        // Sad has 2 matches, Happy has 1. Total = 3. 2/3 = 0.66
        assertEquals(2f/3f, result.confidence, 0.01f)
    }
}
