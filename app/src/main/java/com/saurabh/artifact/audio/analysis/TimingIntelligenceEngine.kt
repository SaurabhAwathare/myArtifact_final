package com.saurabh.artifact.audio.analysis

/**
 * States of user reflection timing.
 */
enum class TimingState {
    FLOW,           // User is actively speaking.
    PROCESSING,     // Short pause, likely thinking.
    STUCK,          // Extended silence, might need a gentle nudge.
    CLOSING         // Long silence, possibly ready to finish.
}

/**
 * Real-time signals for timing evaluation.
 */
data class TimingSignals(
    val currentSilenceDurationMs: Long,
    val sessionDurationSeconds: Long,
    val speechEnergy: Float,
    val timeSinceLastInteractionMs: Long
)

/**
 * Engine that determines the optimal moment for system intervention.
 */
object TimingIntelligenceEngine {

    // Thresholds for state transitions (in milliseconds)
    private const val PROCESSING_THRESHOLD = 500L
    private const val STUCK_THRESHOLD_BASE = 6000L
    private const val CLOSING_THRESHOLD_BASE = 15000L
    
    // Energy threshold to distinguish speech from noise
    private const val SPEECH_ENERGY_THRESHOLD = 0.03f

    /**
     * Evaluates signals to determine the current TimingState, considering experimental overrides.
     */
    fun evaluate(signals: TimingSignals, config: ExperimentConfig = ExperimentConfig()): TimingState {
        val (stuckThreshold, closingThreshold) = when (config.timingVariant) {
            TimingVariant.PATIENT -> 10000L to 20000L
            TimingVariant.ACTIVE -> 4000L to 10000L
            TimingVariant.CONTROL -> STUCK_THRESHOLD_BASE to CLOSING_THRESHOLD_BASE
        }

        return when {
            // If actively speaking (energy above threshold)
            signals.speechEnergy > SPEECH_ENERGY_THRESHOLD -> TimingState.FLOW
            
            // Long silence near the end or after significant time
            signals.currentSilenceDurationMs > closingThreshold -> TimingState.CLOSING
            
            // Extended silence, likely stuck
            signals.currentSilenceDurationMs > stuckThreshold -> TimingState.STUCK
            
            // Short silence, likely processing/thinking
            signals.currentSilenceDurationMs > PROCESSING_THRESHOLD -> TimingState.PROCESSING
            
            // Default to flow or processing for very short gaps
            else -> TimingState.FLOW
        }
    }
}
