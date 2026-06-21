package com.saurabh.artifact.ui.components.waveform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.saurabh.artifact.util.WaveformProcessor

/**
 * Utility for processing and memoizing waveform data specifically for Feed rendering.
 */
object FeedWaveformRenderer {

    /**
     * Resamples amplitude data to a target size suitable for static feed display.
     * Uses a deterministic approach to ensure stability during scroll.
     */
    fun downsample(source: List<Float>, targetSize: Int): List<Float> {
        if (source.isEmpty()) return List(targetSize) { 0.1f }
        if (source.size == targetSize) return source

        val step = source.size.toFloat() / targetSize
        return List(targetSize) { i ->
            val index = (i * step).toInt().coerceIn(0, source.size - 1)
            source[index]
        }
    }

    /**
     * Fallback pattern for artifacts without amplitude data.
     * Generates a stable pseudo-random pattern based on the artifact ID.
     */
    fun generatePlaceholderPattern(id: String, targetSize: Int): List<Float> {
        val seed = id.hashCode()
        val random = java.util.Random(seed.toLong())
        return List(targetSize) { (random.nextFloat() * 0.7f + 0.2f) }
    }
}

/**
 * Memoizes a waveform representation of the given amplitudes.
 */
@Composable
fun rememberFeedWaveform(
    id: String,
    amplitudeData: List<Float>?,
    barCount: Int = 40
): List<Float> {
    return remember(id, amplitudeData, barCount) {
        if (amplitudeData.isNullOrEmpty()) {
            FeedWaveformRenderer.generatePlaceholderPattern(id, barCount)
        } else {
            FeedWaveformRenderer.downsample(amplitudeData, barCount)
        }
    }
}
