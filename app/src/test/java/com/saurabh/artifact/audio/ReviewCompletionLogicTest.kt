package com.saurabh.artifact.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewCompletionLogicTest {

    private fun isUnlocked(furthestPositionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        val percentage = furthestPositionMs.toFloat() / durationMs
        return if (durationMs > 5000) {
            percentage >= 0.95f
        } else {
            percentage >= 0.99f
        }
    }

    @Test
    fun `test 95 percent threshold for long recordings`() {
        val duration = 10000L // 10s
        assertFalse("90% should be locked", isUnlocked(9000L, duration))
        assertTrue("95% should be unlocked", isUnlocked(9500L, duration))
        assertTrue("100% should be unlocked", isUnlocked(10000L, duration))
    }

    @Test
    fun `test 99 percent threshold for short recordings`() {
        val duration = 3000L // 3s
        assertFalse("90% should be locked", isUnlocked(2700L, duration))
        assertFalse("95% should be locked for short recording", isUnlocked(2850L, duration))
        assertTrue("100% should be unlocked", isUnlocked(3000L, duration))
    }

    @Test
    fun `test zero duration`() {
        assertFalse("Zero duration should be locked", isUnlocked(0L, 0L))
    }
}
