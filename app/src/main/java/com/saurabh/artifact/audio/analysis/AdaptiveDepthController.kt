package com.saurabh.artifact.audio.analysis

/**
 * Fluid states representing the user's current cognitive and emotional readiness.
 */
enum class DepthState {
    LIGHT,      // Depth 1-2: User is hesitant, low energy, or just starting.
    BALANCED,   // Depth 2-3: Normal reflective flow.
    DEEP        // Depth 3-4: High engagement, extended responses, ready for challenge.
}

/**
 * Aggregated signals used to determine the appropriate depth.
 */
data class DepthSignals(
    val avgSpeechEnergy: Float = 0f,
    val silenceFrequency: Int = 0, // Number of long silences (> 4s)
    val avgResponseDurationSeconds: Long = 0,
    val skipFrequency: Int = 0, // Number of "Next" taps before 15s
    val historicalAvgDepth: Float = 2.0f
)

/**
 * Engine that maps user signals to a recommended depth range.
 * Operates on-device and focuses on matching user tempo.
 */
object AdaptiveDepthController {

    /**
     * Evaluates current signals to determine the most supportive DepthState.
     */
    fun evaluate(signals: DepthSignals, config: ExperimentConfig = ExperimentConfig()): DepthState {
        var score = 0f

        // Energy & Engagement
        if (signals.avgSpeechEnergy > 0.15f) score += 1f
        if (signals.avgResponseDurationSeconds > 40) score += 1.5f
        if (signals.avgResponseDurationSeconds in 1..9) score -= 1f

        // Friction & Hesitation
        if (signals.silenceFrequency > 3) score -= 1.5f
        if (signals.skipFrequency > 1) score -= 2f

        // Historical Anchor
        val historyWeight = when {
            signals.historicalAvgDepth > 3.0f -> 0.5f
            signals.historicalAvgDepth < 1.5f -> -0.5f
            else -> 0f
        }
        score += historyWeight
        
        // Experimental Biases
        score += when (config.depthVariant) {
            DepthVariant.CHALLENGE -> 0.75f // Easier to reach DEEP
            DepthVariant.GENTLE -> -0.75f   // Stays in LIGHT/BALANCED longer
            DepthVariant.CONTROL -> 0f
        }

        return when {
            score >= 1.5f -> DepthState.DEEP
            score <= -1f -> DepthState.LIGHT
            else -> DepthState.BALANCED
        }
    }
}
