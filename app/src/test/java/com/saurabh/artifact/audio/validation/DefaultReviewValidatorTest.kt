package com.saurabh.artifact.audio.validation

import org.junit.Assert.*
import org.junit.Test

class DefaultReviewValidatorTest {

    private val validator = DefaultReviewValidator()

    @Test
    fun `test valid production rules`() {
        val result = validator.validate(
            coveragePercent = 0.96f,
            effortPercent = 0.80f,
            reachedEnd = true,
            durationMs = 30000L
        )
        assertTrue(result.isValid)
    }

    @Test
    fun `test invalid coverage`() {
        val result = validator.validate(
            coveragePercent = 0.90f,
            effortPercent = 0.80f,
            reachedEnd = true,
            durationMs = 30000L
        )
        assertFalse(result.isValid)
    }

    @Test
    fun `test invalid effort`() {
        val result = validator.validate(
            coveragePercent = 0.96f,
            effortPercent = 0.50f,
            reachedEnd = true,
            durationMs = 30000L
        )
        assertFalse(result.isValid)
    }

    @Test
    fun `test end not reached`() {
        val result = validator.validate(
            coveragePercent = 0.96f,
            effortPercent = 0.80f,
            reachedEnd = false,
            durationMs = 30000L
        )
        assertFalse(result.isValid)
    }

    @Test
    fun `test short artifact relaxation`() {
        val result = validator.validate(
            coveragePercent = 0.995f,
            effortPercent = 0.10f, // Low effort
            reachedEnd = true,
            durationMs = 5000L // 5s
        )
        assertTrue("Short artifacts should relax effort", result.isValid)
    }

    @Test
    fun `test short artifact high coverage requirement`() {
        val result = validator.validate(
            coveragePercent = 0.96f, // Below 99% for short
            effortPercent = 0.10f,
            reachedEnd = true,
            durationMs = 5000L
        )
        assertFalse("Short artifacts require >99% coverage", result.isValid)
    }
}
