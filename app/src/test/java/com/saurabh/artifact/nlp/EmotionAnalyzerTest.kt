package com.saurabh.artifact.nlp

import com.saurabh.artifact.model.Emotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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
        assertEquals(0.0f, result.confidence, 0.1f)
    }

    @Test
    fun `returns Neutral for short unknown text`() {
        val result = analyzer.analyze("Hi.")
        assertEquals(Emotion.NEUTRAL, result.emotion)
    }

    @Test
    fun `returns Unclear for long unknown text`() {
        val result = analyzer.analyze("The weather is quite neutral and the situation is standard.")
        assertEquals(Emotion.UNCLEAR, result.emotion)
    }

    @Test
    fun `detects Mixed emotion when valence crossing occurs`() {
        // "happy" (Positive) vs "sad" (Negative)
        val result = analyzer.analyze("I am happy but also very sad at the same time")
        assertEquals(Emotion.MIXED, result.emotion)
        assertTrue(result.confidence >= 0.5f)
    }

    @Test
    fun `does not detect Mixed for same valence emotions`() {
        // "happy" (Positive) vs "motivated" (Positive)
        // Should pick the dominant one (Motivated is slightly higher in lexicon usually)
        val result = analyzer.analyze("I am happy and motivated!")
        assertTrue(result.emotion == Emotion.HAPPY || result.emotion == Emotion.MOTIVATED)
        // Ensure it's NOT Mixed
        assertTrue(result.emotion != Emotion.MIXED)
    }

    @Test
    fun `detects new lexicon emotions correctly`() {
        val hopefulResult = analyzer.analyze("I am optimistic about the future.")
        assertEquals(Emotion.HOPEFUL, hopefulResult.emotion)

        val gratefulResult = analyzer.analyze("I am so thankful for everything.")
        assertEquals(Emotion.GRATEFUL, gratefulResult.emotion)

        val calmResult = analyzer.analyze("Feeling very peaceful and serene.")
        assertEquals(Emotion.CALM, calmResult.emotion)

        val confusedResult = analyzer.analyze("I am so lost and puzzled right now.")
        assertEquals(Emotion.CONFUSED, confusedResult.emotion)

        val overwhelmedResult = analyzer.analyze("I feel buried and exhausted by all this work.")
        assertEquals(Emotion.OVERWHELMED, overwhelmedResult.emotion)
    }

    @Test
    fun `handles mixed emotions by picking top score if margin is large`() {
        // "happy" (Positive: 1.0) vs "sad", "unhappy" (Negative: 1.0 + 1.1 = 2.1)
        // Margin = (2.1 - 1.0) / 3.1 = 0.35 > 0.25 threshold
        val result = analyzer.analyze("I'm happy but also feeling very sad and unhappy.")
        assertEquals(Emotion.SAD, result.emotion)
    }

    @Test
    fun `handles negation correctly by ignoring negated emotions`() {
        val result = analyzer.analyze("I am not happy")
        assertEquals(Emotion.NEUTRAL, result.emotion)
    }

    @Test
    fun `detects Spanish emotions from expanded lexicon`() {
        val gratefulEs = analyzer.analyze("Estoy muy agradecido", Locale.forLanguageTag("es"))
        assertEquals(Emotion.GRATEFUL, gratefulEs.emotion)

        val calmEs = analyzer.analyze("Me siento tranquilo", Locale.forLanguageTag("es"))
        assertEquals(Emotion.CALM, calmEs.emotion)
    }

    @Test
    fun `boosts intensity with capitalization and exclamation marks`() {
        val shoutingResult = analyzer.analyze("I AM SO HAPPY!!!")
        assertEquals(Emotion.HAPPY, shoutingResult.emotion)
        assertTrue(shoutingResult.confidence > 0.8f)
    }
}
