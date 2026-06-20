package com.saurabh.artifact.domain.review.comments

import com.saurabh.artifact.domain.review.EngagementEvidence
import org.junit.Assert.*
import org.junit.Test
import java.util.BitSet

class CommentUnlockValidatorTest {

    private val validator = CommentUnlockValidator()
    private val policy = CommentUnlockPolicy(minCoverage = 0.95f, requireReachedEnd = true)

    @Test
    fun `test unlock requires high coverage and reaching end`() {
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
    fun `test unlock fails if end not reached`() {
        val evidence = EngagementEvidence(
            artifactId = "a",
            versionTag = "v1",
            durationMs = 10000L,
            coverage = BitSet(20).apply { set(0, 20) }, 
            hasReachedEnd = false
        )
        
        val result = validator.validate(evidence, policy)
        assertFalse(result.isValid)
    }

    @Test
    fun `test flexible policy for comments`() {
        // Simulating a future where comments only need 90% and NO reached end
        val flexiblePolicy = CommentUnlockPolicy(minCoverage = 0.90f, requireReachedEnd = false)
        val evidence = EngagementEvidence(
            artifactId = "a",
            versionTag = "v1",
            durationMs = 10000L,
            coverage = BitSet(20).apply { set(0, 18) }, // 90%
            hasReachedEnd = false
        )
        
        val result = validator.validate(evidence, flexiblePolicy)
        assertTrue("Should be valid under flexible policy", result.isValid)
    }
}
