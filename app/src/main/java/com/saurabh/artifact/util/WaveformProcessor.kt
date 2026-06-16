package com.saurabh.artifact.util

import java.io.File
import kotlin.math.ln
import kotlin.math.max

/**
 * Utility for processing audio amplitude data for visual resonance.
 * Focuses on smoothing, normalization, and contextual sampling.
 */
object WaveformProcessor {

    enum class SamplingMode {
        COMPRESS, // Squeeze everything into targetSize (Playback)
        SCROLL    // Take last targetSize samples (Recording)
    }

    /**
     * Normalizes and smooths raw amplitude data.
     * @param rawAmplitudes List of raw amplitude values (usually 0 to 1 or 0 to 32767).
     * @param targetSize The number of bars to return.
     * @param mode Sampling strategy (Compress or Scroll).
     * @return A list of normalized and smoothed amplitudes in the range [0.1, 1.0].
     */
    fun process(
        rawAmplitudes: List<Float>,
        targetSize: Int,
        mode: SamplingMode = SamplingMode.COMPRESS
    ): List<Float> {
        if (rawAmplitudes.isEmpty()) return List(targetSize) { 0.1f }

        // 1. Sampling & Windowing
        val sampled = when (mode) {
            SamplingMode.COMPRESS -> {
                if (rawAmplitudes.size > targetSize) {
                    val chunkSize = rawAmplitudes.size / targetSize
                    List(targetSize) { i ->
                        val chunk = rawAmplitudes.subList(i * chunkSize, (i + 1) * chunkSize)
                        chunk.maxOrNull() ?: 0f
                    }
                } else {
                    val scale = targetSize.toFloat() / rawAmplitudes.size
                    List(targetSize) { i ->
                        val index = (i / scale).toInt().coerceIn(0, rawAmplitudes.size - 1)
                        rawAmplitudes[index]
                    }
                }
            }
            SamplingMode.SCROLL -> {
                if (rawAmplitudes.size >= targetSize) {
                    // Take the most recent samples
                    rawAmplitudes.takeLast(targetSize)
                } else {
                    // Pad with leading zeros (silence) until we have enough data
                    List(targetSize - rawAmplitudes.size) { 0f } + rawAmplitudes
                }
            }
        }

        return processSampled(sampled)
    }

    /**
     * Internal post-processing for already sampled data.
     * Includes Logarithmic Scaling, Normalization, and Smoothing.
     */
    private fun processSampled(sampled: List<Float>): List<Float> {
        // 2. Logarithmic Scaling (Soft Compression)
        // Helps quiet parts be visible and loud parts not clip too harshly.
        val logScaled = sampled.map { amp ->
            if (amp <= 0) 0f else ln(1f + (amp * 10f)) // Amplify a bit before log
        }

        // 3. Normalization to [0.1, 1.0]
        val maxAmp = logScaled.maxOrNull() ?: 1f
        val normalized = logScaled.map { amp ->
            max(0.1f, amp / if (maxAmp == 0f) 1f else maxAmp)
        }

        // 4. Smoothing (Moving Average)
        return applyMovingAverage(normalized)
    }

    /**
     * Extracts waveform data from a raw PCM (16-bit) file incrementally.
     */
    fun extractFromPcm(pcmFile: File, targetSize: Int): List<Float> {
        if (!pcmFile.exists() || pcmFile.length() == 0L) return emptyList()

        val fileLength = pcmFile.length()
        val headerOffset = if (pcmFile.extension.lowercase() == "wav") 44L else 0L
        val dataLength = fileLength - headerOffset
        val totalSamples = dataLength / 2

        // Fallback for very small files or empty data
        if (totalSamples <= 0) return List(targetSize) { 0.1f }

        if (totalSamples < targetSize) {
            val rawAmplitudes = readAllSamples(pcmFile, headerOffset)
            return process(rawAmplitudes, targetSize)
        }

        val samplesPerPeak = totalSamples / targetSize
        val peaks = mutableListOf<Float>()

        try {
            pcmFile.inputStream().use { inputStream ->
                inputStream.skip(headerOffset)
                val buffer = ByteArray(8192)
                var currentMax = 0f
                var samplesProcessed = 0L
                var totalSamplesProcessed = 0L

                while (peaks.size < targetSize) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break

                    for (i in 0 until bytesRead step 2) {
                        if (i + 1 >= bytesRead) break
                        
                        // 16-bit PCM Little Endian
                        val low = buffer[i].toInt() and 0xff
                        val high = buffer[i + 1].toInt()
                        val sample = (high shl 8) or low
                        
                        val amplitude = kotlin.math.abs(sample).toFloat() / 32768f
                        if (amplitude > currentMax) currentMax = amplitude
                        
                        samplesProcessed++
                        totalSamplesProcessed++

                        if (samplesProcessed >= samplesPerPeak && peaks.size < targetSize) {
                            peaks.add(currentMax)
                            currentMax = 0f
                            samplesProcessed = 0
                        }
                        
                        if (peaks.size == targetSize) break
                    }
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }

        // Pad if we somehow didn't reach targetSize
        while (peaks.size < targetSize) {
            peaks.add(0.1f)
        }

        return processSampled(peaks)
    }

    private fun readAllSamples(pcmFile: File, headerOffset: Long): List<Float> {
        val rawAmplitudes = mutableListOf<Float>()
        val buffer = ByteArray(4096)
        try {
            pcmFile.inputStream().use { inputStream ->
                inputStream.skip(headerOffset)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    for (i in 0 until bytesRead step 2) {
                        if (i + 1 >= bytesRead) break
                        val low = buffer[i].toInt() and 0xff
                        val high = buffer[i + 1].toInt()
                        val sample = (high shl 8) or low
                        rawAmplitudes.add(kotlin.math.abs(sample).toFloat() / 32768f)
                    }
                }
            }
        } catch (_: Exception) {}
        return rawAmplitudes
    }

    private fun applyMovingAverage(data: List<Float>): List<Float> {
        val windowSize = 3
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
