package com.saurabh.artifact.util

import kotlin.math.ln
import kotlin.math.max

/**
 * Utility for processing audio amplitude data for visual resonance.
 * Focuses on smoothing, normalization, and contextual sampling.
 */
object WaveformProcessor {

    /**
     * Normalizes and smooths raw amplitude data.
     * @param rawAmplitudes List of raw amplitude values (usually 0 to 1 or 0 to 32767).
     * @param targetSize The number of bars to return.
     * @return A list of normalized and smoothed amplitudes in the range [0.0, 1.0].
     */
    fun process(
        rawAmplitudes: List<Float>,
        targetSize: Int
    ): List<Float> {
        if (rawAmplitudes.isEmpty()) return List(targetSize) { 0.1f }

        // 1. Sampling & Down-averaging
        val sampled = if (rawAmplitudes.size > targetSize) {
            val chunkSize = rawAmplitudes.size / targetSize
            List(targetSize) { i ->
                val chunk = rawAmplitudes.subList(i * chunkSize, (i + 1) * chunkSize)
                chunk.average().toFloat()
            }
        } else {
            // Upsample if needed (simple linear interpolation or repeat)
            val scale = targetSize.toFloat() / rawAmplitudes.size
            List(targetSize) { i ->
                val index = (i / scale).toInt().coerceIn(0, rawAmplitudes.size - 1)
                rawAmplitudes[index]
            }
        }

        // 2. Logarithmic Scaling (Soft Compression)
        // Helps quiet parts be visible and loud parts not clip too harshly.
        val logScaled = sampled.map { amp ->
            if (amp <= 0) 0f else ln(1f + amp)
        }

        // 3. Normalization to [0.1, 1.0]
        val maxAmp = logScaled.maxOrNull() ?: 1f
        val normalized = logScaled.map { amp ->
            max(0.1f, amp / if (maxAmp == 0f) 1f else maxAmp)
        }

        // 4. Smoothing (Moving Average)
        return applyMovingAverage(normalized, windowSize = 3)
    }

    private fun applyMovingAverage(data: List<Float>, windowSize: Int): List<Float> {
        if (data.size < windowSize) return data
        val result = mutableListOf<Float>()
        val halfWindow = windowSize / 2

        for (i in data.indices) {
            val start = max(0, i - halfWindow)
            val end = (i + halfWindow).coerceAtMost(data.size - 1)
            val window = data.subList(start, end + 1)
            result.add(window.average().toFloat())
        }
        return result
    }
}
