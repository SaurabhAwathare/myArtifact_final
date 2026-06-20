package com.saurabh.artifact.domain.review.publishing

import com.saurabh.artifact.domain.review.EngagementEvidence
import org.junit.Assert.*
import org.junit.Test
import java.util.BitSet

class PublishingReviewValidatorTest {

    private val validator = PublishingReviewValidator()
    private val policy = PublishingReviewPolicy(minCoverage = 0.95f, requireReachedEnd = true)

    @Test
    fun `test publishing requires high coverage and reaching end`() {
        val evidence = EngagementEvidence(
            artifactId = "a",
            versionTag = "v1",
            durationMs = 10000L,
            coverage = BitSet(20).apply { set(0, 20) }, // 100%
            hasReachedEnd = true
        )
        
        val result = validator.validate(evidence, policy)
        assertTrue(result.isValid)
    }

    @Test
    fun `test publishing fails if end not reached even if 100% coverage`() {
        val evidence = EngagementEvidence(
            artifactId = "a",
            versionTag = "v1",
            durationMs = 10000L,
            coverage = BitSet(20).apply { set(0, 20) }, // 100%
            hasReachedEnd = false
        )
        
        val result = validator.validate(evidence, policy)
        assertFalse("Should be invalid if reachedEnd is false", result.isValid)
    }

    @Test
    fun `test publishing fails if coverage low`() {
        val evidence = EngagementEvidence(
            artifactId = "a",
            versionTag = "v1",
            durationMs = 10000L,
            coverage = BitSet(20).apply { set(0, 10) }, // 50%
            hasReachedEnd = true
        )
        
        val result = validator.validate(evidence, policy)
        assertFalse("Should be invalid if coverage is 50%", result.isValid)
    }
}
