package com.saurabh.artifact.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewCompletionLogicTest {

    private fun isUnlocked(p1: Long, p2: Long, timeListenedMs: Long, durationMs: Long, isEnded: Boolean = false): Boolean {
        if (durationMs <= 0) return false
        if (isEnded) return true
        val coverageCount = java.lang.Long.bitCount(p1) + java.lang.Long.bitCount(p2)
        val coverage = coverageCount.toFloat() / 100f
        val effort = timeListenedMs.toFloat() / durationMs
        return coverage >= 0.90f && effort >= 0.70f
    }

    @Test
    fun `test intentional review gate logic with 100 segments`() {
        val duration = 10000L // 10s
        
        // Scenario 1: Scrubbed to 100% (No effort, high coverage on one bit)
        assertFalse("Scrubbed to end should be locked", isUnlocked(0L, 1L shl 35, 1000L, duration))
        
        // Scenario 2: High coverage but missing segments (85 segments)
        val p1 = -1L // 64 bits
        var p2 = 0L
        for (i in 0..20) p2 = p2 or (1L shl i) // 64 + 21 = 85 bits
        assertFalse("85% coverage should be locked", isUnlocked(p1, p2, 8000L, duration))
        
        // Scenario 3: High coverage (91 segments) but low effort (50%)
        p2 = 0L
        for (i in 0..26) p2 = p2 or (1L shl i) // 64 + 27 = 91 bits
        assertFalse("Low effort should be locked", isUnlocked(p1, p2, 5000L, duration))
        
        // Scenario 4: Valid review (91 segments, 75% effort)
        assertTrue("High coverage and effort should be unlocked", isUnlocked(p1, p2, 7500L, duration))
        
        // Scenario 5: Edge case 90% coverage (Exactly 90 segments)
        p2 = 0L
        for (i in 0..25) p2 = p2 or (1L shl i) // 64 + 26 = 90 bits
        assertTrue("90% coverage should be unlocked", isUnlocked(p1, p2, 7000L, duration))
        
        // Scenario 6: Completion via STATE_ENDED
        assertTrue("Completion via end signal should be unlocked", isUnlocked(0L, 0L, 100L, duration, true))
    }

    @Test
    fun `test zero duration`() {
        assertFalse("Zero duration should be locked", isUnlocked(-1L, -1L, 1000L, 0L))
    }
}
