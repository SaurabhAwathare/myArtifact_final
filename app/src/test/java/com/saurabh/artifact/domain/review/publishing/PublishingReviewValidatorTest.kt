package com.saurabh.artifact.domain.review.publishing

import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.domain.review.ReviewTrackingVersion
import org.junit.Assert.*
import org.junit.Test
import java.util.BitSet

class PublishingReviewValidatorTest {

    private val validator = PublishingReviewValidator()
    private val policy = PublishingReviewPolicy(minCoverage = 0.95f, requireReachedEnd = true)

    @Test
    fun `test publishing requires high coverage and reaching end legacy`() {
        val evidence = EngagementEvidence(
            artifactId = "a",
            versionTag = "v1",
            durationMs = 10000L,
            coverage = BitSet(20).apply { set(0, 20) }, // 20 segments of 500ms = 10s
            hasReachedEnd = true,
            reviewTrackingVersion = ReviewTrackingVersion.LEGACY_BUCKETED
        )
        
        val result = validator.validate(evidence, policy)
        assertTrue(result.isValid)
    }

    @Test
    fun `test publishing requires high coverage and reaching end version 2`() {
        val evidence = EngagementEvidence(
            artifactId = "a",
            versionTag = "v1",
            durationMs = 10000L,
            coverage = BitSet(10).apply { set(0, 10) }, // 10 segments of 1000ms = 10s
            hasReachedEnd = true,
            reviewTrackingVersion = ReviewTrackingVersion.FIXED_ONE_SECOND
        )
        
        val result = validator.validate(evidence, policy)
        assertTrue(result.isValid)
    }

    @Test
    fun `test publishing fails if version 2 uses legacy segment count`() {
        val evidence = EngagementEvidence(
            artifactId = "a",
            versionTag = "v1",
            durationMs = 10000L,
            coverage = BitSet(10).apply { set(0, 5) }, // 5 out of 10 segments = 50%
            hasReachedEnd = true,
            reviewTrackingVersion = ReviewTrackingVersion.FIXED_ONE_SECOND
        )
        
        val result = validator.validate(evidence, policy)
        assertFalse("Should be invalid if coverage is 50%", result.isValid)
    }

    @Test
    fun `test publishing fails if end not reached even if 100% coverage`() {
        val evidence = EngagementEvidence(
            artifactId = "a",
            versionTag = "v1",
            durationMs = 10000L,
            coverage = BitSet(10).apply { set(0, 10) }, // 100%
            hasReachedEnd = false,
            reviewTrackingVersion = ReviewTrackingVersion.FIXED_ONE_SECOND
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
            coverage = BitSet(10).apply { set(0, 5) }, // 50%
            hasReachedEnd = true,
            reviewTrackingVersion = ReviewTrackingVersion.FIXED_ONE_SECOND
        )
        
        val result = validator.validate(evidence, policy)
        assertFalse("Should be invalid if coverage is 50%", result.isValid)
    }
}
