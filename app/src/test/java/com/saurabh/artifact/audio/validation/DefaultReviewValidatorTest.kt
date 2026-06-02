package com.saurabh.artifact.audio.validation

import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.domain.review.ReviewPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.BitSet

class DefaultReviewValidatorTest {

    private val validator = DefaultReviewValidator()

    @Test
    fun `test valid production rules`() {
        // Duration 30s, Segment size 500ms -> 60 segments
        val evidence = EngagementEvidence(
            artifactId = "a",
            versionTag = "v",
            durationMs = 30000L,
            coverage = BitSet(60).apply { set(0, 60) }, // 100% coverage
            effortMap = mapOf(1.0f to 25000L), // 83% effort
            hasReachedEnd = true
        )
        val policy = ReviewPolicy()
        
        val result = validator.validate(evidence, policy)
        assertTrue("Validation failed. Coverage: ${result.coveragePercent}, Effort: ${result.effortPercent}", result.isValid)
    }

    @Test
    fun `test invalid coverage`() {
        val evidence = EngagementEvidence(
            artifactId = "a",
            versionTag = "v",
            durationMs = 30000L,
            coverage = BitSet(60).apply { set(0, 30) }, // 50% coverage (need 90%)
            effortMap = mapOf(1.0f to 25000L),
            hasReachedEnd = true
        )
        val policy = ReviewPolicy(minCoverage = 0.90f)
        
        val result = validator.validate(evidence, policy)
        assertFalse(result.isValid)
    }

    @Test
    fun `test speed penalized effort`() {
        val evidence = EngagementEvidence(
            artifactId = "a",
            versionTag = "v",
            durationMs = 30000L,
            coverage = BitSet(60).apply { set(0, 60) },
            effortMap = mapOf(4.0f to 7500L), // 30s at 4x = 7.5s wall clock
            hasReachedEnd = true
        )
        val policy = ReviewPolicy(minEffort = 0.70f, maxSpeedPenaltyThreshold = 2.0f)
        
        // Adjusted Effort: 7.5s * (2.0 / 4.0) = 3.75s
        // Effort %: 3.75 / 30 = 0.125 (12.5%)
        val result = validator.validate(evidence, policy)
        assertFalse("Should fail due to speed penalty", result.isValid)
        assertTrue(result.effortPercent < 0.15f)
    }

    @Test
    fun `test end not reached`() {
        val evidence = EngagementEvidence(
            artifactId = "a",
            versionTag = "v",
            durationMs = 30000L,
            coverage = BitSet(60).apply { set(0, 60) },
            effortMap = mapOf(1.0f to 25000L),
            hasReachedEnd = false
        )
        val policy = ReviewPolicy(requireReachedEnd = true)
        
        val result = validator.validate(evidence, policy)
        assertFalse(result.isValid)
    }
}
