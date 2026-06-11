package com.saurabh.artifact.service

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SafetyEvaluatorTest {

    private lateinit var evaluator: SafetyEvaluator

    @Before
    fun setup() {
        evaluator = SafetyEvaluator()
    }

    @Test
    fun `direct high risk match returns HIGH level with full confidence`() {
        val result = evaluator.evaluate("I want to kill myself")
        assertEquals(SafetyLevel.HIGH, result.level)
        assertTrue(result.isCrisis)
        assertEquals(1.0f, result.confidence)
    }

    @Test
    fun `negated high risk match downgrades to MEDIUM level`() {
        val result = evaluator.evaluate("I am not going to kill myself")
        // "not" should reduce confidence by 0.5, bringing it to 0.5
        // Level should be MEDIUM since 0.5 < 0.6
        assertEquals(SafetyLevel.MEDIUM, result.level)
        assertFalse(result.isCrisis)
        assertEquals(0.5f, result.confidence)
    }

    @Test
    fun `high risk match with context word reduces confidence but may stay HIGH`() {
        // "movie" reduces confidence by 0.3 -> 0.7
        // 0.7 >= 0.6, so it should stay HIGH
        val result = evaluator.evaluate("The movie was intense but I want to kill myself")
        assertEquals(SafetyLevel.HIGH, result.level)
        assertTrue(result.isCrisis)
        assertEquals(0.7f, result.confidence)
    }

    @Test
    fun `combined negation and context significantly reduces confidence`() {
        // Negation (-0.5) + Context (-0.3) = 0.2
        val result = evaluator.evaluate("I am not thinking about suicide because of that movie")
        assertEquals(SafetyLevel.MEDIUM, result.level)
        assertFalse(result.isCrisis)
        assertEquals(0.2f, result.confidence, 0.01f)
    }

    @Test
    fun `medium risk match with no inhibitors`() {
        val result = evaluator.evaluate("I feel hopeless and trapped")
        assertEquals(SafetyLevel.MEDIUM, result.level)
        assertEquals(1.0f, result.confidence)
    }

    @Test
    fun `low distress keywords return LOW level`() {
        val result = evaluator.evaluate("I am feeling a bit sad and tired today")
        assertEquals(SafetyLevel.LOW, result.level)
    }

    @Test
    fun `normal text returns LOW level`() {
        val result = evaluator.evaluate("I had a great day at the park")
        assertEquals(SafetyLevel.LOW, result.level)
    }

    @Test
    fun `filterAIOutput removes minimizing language`() {
        val input = "You should just try to relax."
        val output = evaluator.filterAIOutput(input)
        // "You should" -> "how might you"
        // "just" -> removed
        // "try to" -> "consider if"
        // Result: "How might you consider if relax?" (based on the regex logic in the class)
        // Let's check if "just" is gone.
        assertFalse(output.contains("just", ignoreCase = true))
    }

    @Test
    fun `filterAIOutput converts Why to What makes`() {
        val input = "Why do you feel this way?"
        val output = evaluator.filterAIOutput(input)
        assertTrue(output.startsWith("What makes", ignoreCase = true))
    }
}
