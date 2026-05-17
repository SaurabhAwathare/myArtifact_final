package com.saurabh.artifact.audio.analysis

import kotlin.math.sqrt

/**
 * Heuristic-based analyzer for detecting emotional cues from vocal characteristics.
 * Version 1 focuses on energy (amplitude) as a proxy for emotional intensity.
 */
object AudioEmotionAnalyzer {

    enum class VoiceEmotion(val label: String, val insight: String, val state: EmotionalState) {
        LOW_ENERGY("calm", "You sound calm and grounded", EmotionalState.CALM),
        MEDIUM_ENERGY("steady", "Your voice is steady", EmotionalState.ENGAGED),
        HIGH_ENERGY("energetic", "You sound quite energetic", EmotionalState.ENGAGED),
        QUIET("low ऊर्जा", "You sound a bit low on energy", EmotionalState.HESITANT),
        UNKNOWN("neutral", "", EmotionalState.CALM)
    }

    /**
     * Thresholds for amplitude-based classification.
     * These are normalized values between 0.0 and 1.0.
     */
    private const val THRESHOLD_QUIET = 0.05f
    private const val THRESHOLD_MEDIUM = 0.25f
    private const val THRESHOLD_HIGH = 0.60f

    /**
     * Analyzes a list of amplitude values to infer an emotional state.
     * Optimized to only process a recent window of data to maintain performance.
     */
    fun analyze(amplitudes: List<Float>): VoiceEmotion {
        if (amplitudes.isEmpty()) return VoiceEmotion.UNKNOWN

        // Focus on the last 3-5 seconds of audio to detect current state
        // 20 ticks per second * 5 = 100 samples
        val windowSize = 100
        val recentSamples = if (amplitudes.size > windowSize) {
            amplitudes.subList(amplitudes.size - windowSize, amplitudes.size)
        } else {
            amplitudes
        }

        // Use a single-pass calculation to avoid multiple list traversals
        var sumSquares = 0f
        var activeCount = 0
        
        recentSamples.forEach { 
            if (it > 0.001f) {
                sumSquares += it * it
                activeCount++
            }
        }

        if (activeCount == 0) return VoiceEmotion.QUIET
        
        val rms = sqrt(sumSquares / activeCount)

        return when {
            rms < THRESHOLD_QUIET -> VoiceEmotion.QUIET
            rms < THRESHOLD_MEDIUM -> VoiceEmotion.LOW_ENERGY
            rms < THRESHOLD_HIGH -> VoiceEmotion.MEDIUM_ENERGY
            else -> VoiceEmotion.HIGH_ENERGY
        }
    }
}
