package com.saurabh.artifact.util

import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Utility for processing audio amplitude data for visual resonance.
 * Uses Root Mean Square (RMS) energy extraction to preserve dynamic contrast
 * between loud and quiet sections across short and long recordings.
 */
object WaveformProcessor {

    enum class SamplingMode {
        COMPRESS, // Squeeze everything into targetSize (Playback)
        SCROLL    // Take last targetSize samples (Recording)
    }

    /**
     * Normalizes and scales raw amplitude data.
     * @param rawAmplitudes List of raw amplitude values (usually 0 to 1).
     * @param targetSize The number of bars to return.
     * @param mode Sampling strategy (Compress or Scroll).
     * @return A list of normalized amplitudes in the range [0.1, 1.0].
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
                        val sumSq = chunk.fold(0.0) { acc, v -> acc + (v * v) }
                        sqrt(sumSq / chunk.size).toFloat()
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
     * Internal post-processing for sampled RMS values.
     * Maps energy levels to [0.1, 1.0] while maintaining noise ceiling protection for silence.
     */
    private fun processSampled(sampled: List<Float>): List<Float> {
        val maxRms = sampled.maxOrNull() ?: 0f
        // Noise ceiling protection: prevents scaling silence / background noise up to 1.0
        val minNoiseCeiling = 0.01f
        val referenceMax = max(maxRms, minNoiseCeiling)

        return sampled.map { amp ->
            val norm = (amp.coerceAtLeast(0f) / referenceMax)
            (0.1f + 0.9f * norm).coerceIn(0.1f, 1.0f)
        }
    }

    /**
     * Extracts waveform data from a raw PCM (16-bit) file incrementally.
     * Computes RMS energy per analysis window to preserve temporal dynamics.
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
                var sumSquares = 0.0
                var samplesInChunk = 0L

                while (peaks.size < targetSize) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break

                    for (i in 0 until bytesRead step 2) {
                        if (i + 1 >= bytesRead) break

                        // 16-bit PCM Little Endian
                        val low = buffer[i].toInt() and 0xff
                        val high = buffer[i + 1].toInt()
                        val sample = (high shl 8) or low

                        val normalizedSample = sample.toFloat() / 32768f
                        sumSquares += (normalizedSample * normalizedSample)
                        samplesInChunk++

                        if (samplesInChunk >= samplesPerPeak && peaks.size < targetSize) {
                            val rms = sqrt(sumSquares / samplesInChunk).toFloat()
                            peaks.add(rms)
                            sumSquares = 0.0
                            samplesInChunk = 0L
                        }

                        if (peaks.size == targetSize) break
                    }
                }

                if (samplesInChunk > 0 && peaks.size < targetSize) {
                    val rms = sqrt(sumSquares / samplesInChunk).toFloat()
                    peaks.add(rms)
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }

        // Pad if we didn't reach targetSize
        while (peaks.size < targetSize) {
            peaks.add(0f)
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
}
