package com.saurabh.artifact.audio.analysis

import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A delayed insight that waits for a specific moment to be revealed.
 */
data class DelayedInsight(
    val id: String = UUID.randomUUID().toString(),
    val insight: ProgressInsight,
    val createdAt: Long = System.currentTimeMillis(),
    val revealAt: Long,
    val isRevealed: Boolean = false
)

/**
 * Logic for deciding when an insight should be surfaced to the user.
 */
object InsightScheduler {

    private const val MIN_DELAY_MINUTES = 30L

    /**
     * Calculates a reflective reveal time for a new insight.
     * Strategy: Reveal after a minimum rest period (30m) or on the next likely
     * reflection window (e.g., 24h later).
     */
    fun schedule(insight: ProgressInsight): DelayedInsight {
        val now = System.currentTimeMillis()
        
        // Strategy: Reveal after at least 30 minutes to separate from recording effort
        val revealTime = now + TimeUnit.MINUTES.toMillis(MIN_DELAY_MINUTES)

        return DelayedInsight(
            insight = insight,
            revealAt = revealTime
        )
    }

    /**
     * Determines if a pending insight is ready to be shown.
     */
    fun shouldReveal(pending: DelayedInsight?): Boolean {
        if ((pending == null) || (pending.isRevealed)) return false
        
        val now = System.currentTimeMillis()
        return now >= pending.revealAt
    }
}
