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
            hasReachedEnd = true
        )
        val policy = ReviewPolicy()
        
        val result = validator.validate(evidence, policy)
        assertTrue("Validation failed. Coverage: ${result.coveragePercent}", result.isValid)
    }

    @Test
    fun `test invalid coverage`() {
        val evidence = EngagementEvidence(
            artifactId = "a",
            versionTag = "v",
            durationMs = 30000L,
            coverage = BitSet(60).apply { set(0, 30) }, // 50% coverage (need 90%)
            hasReachedEnd = true
        )
        val policy = ReviewPolicy(minCoverage = 0.90f)
        
        val result = validator.validate(evidence, policy)
        assertFalse(result.isValid)
    }

    @Test
    fun `test end not reached`() {
        val evidence = EngagementEvidence(
            artifactId = "a",
            versionTag = "v",
            durationMs = 30000L,
            coverage = BitSet(60).apply { set(0, 60) },
            hasReachedEnd = false
        )
        val policy = ReviewPolicy(requireReachedEnd = true)
        
        val result = validator.validate(evidence, policy)
        assertFalse(result.isValid)
    }
}
