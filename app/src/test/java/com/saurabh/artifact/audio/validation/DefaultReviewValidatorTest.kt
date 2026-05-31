package com.saurabh.artifact.audio.validation

import com.saurabh.artifact.domain.review.ReviewEvidence
import com.saurabh.artifact.domain.review.ReviewPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.BitSet

class DefaultReviewValidatorTest {

    private val validator = DefaultReviewValidator()

    @Test
    fun `test valid production rules`() {
        val evidence = ReviewEvidence(
            artifactId = "a",
            versionTag = "v",
            durationMs = 30000L,
            coverage = BitSet(6).apply { set(0, 6) }, // 100% coverage
            effortMap = mapOf(1.0f to 25000L), // 83% effort
            hasReachedEnd = true
        )
        val policy = ReviewPolicy()
        
        val result = validator.validate(evidence, policy)
        assertTrue(result.isValid)
    }

    @Test
    fun `test invalid coverage`() {
        val evidence = ReviewEvidence(
            artifactId = "a",
            versionTag = "v",
            durationMs = 30000L,
            coverage = BitSet(6).apply { set(0, 3) }, // 50% coverage
            effortMap = mapOf(1.0f to 25000L),
            hasReachedEnd = true
        )
        val policy = ReviewPolicy(minCoverage = 0.95f)
        
        val result = validator.validate(evidence, policy)
        assertFalse(result.isValid)
    }

    @Test
    fun `test speed penalized effort`() {
        val evidence = ReviewEvidence(
            artifactId = "a",
            versionTag = "v",
            durationMs = 30000L,
            coverage = BitSet(6).apply { set(0, 6) },
            effortMap = mapOf(4.0f to 7500L), // 30s at 4x = 7.5s wall clock
            hasReachedEnd = true
        )
        val policy = ReviewPolicy(minEffort = 0.75f, maxSpeedPenaltyThreshold = 2.0f)
        
        // Adjusted Effort: 7.5s * (2.0 / 4.0) = 3.75s
        // Effort %: 3.75 / 30 = 0.125 (12.5%)
        val result = validator.validate(evidence, policy)
        assertFalse("Should fail due to speed penalty", result.isValid)
        assertTrue(result.effortPercent < 0.15f)
    }

    @Test
    fun `test end not reached`() {
        val evidence = ReviewEvidence(
            artifactId = "a",
            versionTag = "v",
            durationMs = 30000L,
            coverage = BitSet(6).apply { set(0, 6) },
            effortMap = mapOf(1.0f to 25000L),
            hasReachedEnd = false
        )
        val policy = ReviewPolicy(requireReachedEnd = true)
        
        val result = validator.validate(evidence, policy)
        assertFalse(result.isValid)
    }
}
