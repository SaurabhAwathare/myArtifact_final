package com.saurabh.artifact.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WaveformProcessorTest {

    @Test
    fun `process scales small amplitudes up to minimum threshold`() {
        val raw = listOf(0.01f, 0.02f, 0.01f)
        val processed = WaveformProcessor.process(raw, targetSize = 3)
        
        // Should be at least 0.1f
        processed.forEach { amp ->
            assertTrue("Amplitude $amp should be >= 0.1f", amp >= 0.1f)
        }
    }

    @Test
    fun `process handles empty input gracefully`() {
        val processed = WaveformProcessor.process(emptyList(), targetSize = 10)
        assertEquals(10, processed.size)
        processed.forEach { assertEquals(0.1f, it) }
    }

    @Test
    fun `process downsamples correctly using RMS energy`() {
        // [0.1, 0.9, 0.1, 0.1] -> target size 2
        // Chunk 1: [0.1, 0.9] -> RMS is ~0.64
        // Chunk 2: [0.1, 0.1] -> RMS is 0.1
        val raw = listOf(0.1f, 0.9f, 0.1f, 0.1f)
        val processed = WaveformProcessor.process(raw, targetSize = 2)
        
        assertEquals(2, processed.size)
        assertTrue("First chunk should be significantly higher than second chunk", processed[0] > processed[1])
    }

    @Test
    fun `extractFromPcm parses fake pcm data correctly`() {
        val tempFile = File.createTempFile("test", ".pcm")
        try {
            // Write 1000 samples of 16-bit PCM (Little Endian)
            // Alternating between 0 and 16384 (0.5 normalized)
            val buffer = ByteBuffer.allocate(2000).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until 1000) {
                if (i % 2 == 0) buffer.putShort(0)
                else buffer.putShort(16384)
            }
            tempFile.writeBytes(buffer.array())

            val waveform = WaveformProcessor.extractFromPcm(tempFile, targetSize = 10)
            assertEquals(10, waveform.size)
            
            // Check that values are within valid bounded range [0.1, 1.0]
            waveform.forEach { amp ->
                assertTrue("Amplitude $amp should be in range [0.1, 1.0]", amp in 0.1f..1.0f)
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `extractFromPcm handles large files incrementally`() {
        val tempFile = File.createTempFile("large_test", ".pcm")
        try {
            // Write 100,000 samples (200 KB)
            val sampleCount = 100_000
            val targetSize = 100

            val buffer = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(sampleCount) {
                buffer.putShort(20000) // ~0.6 amplitude signal
            }
            tempFile.writeBytes(buffer.array())

            val waveform = WaveformProcessor.extractFromPcm(tempFile, targetSize = targetSize)
            
            assertEquals("Should return exactly targetSize peaks", targetSize, waveform.size)
            waveform.forEach { amp ->
                assertTrue("Constant signal should yield high amplitude, got $amp", amp > 0.8f)
            }

        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `TEST 1 - Alternating loud and quiet audio preserves temporal dynamics`() {
        val tempFile = File.createTempFile("alternating_test", ".pcm")
        try {
            val totalSamples = 40_000 // 4 sections of 10,000 samples each
            val targetSize = 40
            val buffer = ByteBuffer.allocate(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until totalSamples) {
                val section = i / 10_000
                if (section % 2 == 0) {
                    // Quiet section (~0.05 amplitude)
                    buffer.putShort(1600)
                } else {
                    // Loud section (~0.8 amplitude)
                    buffer.putShort(26000)
                }
            }
            tempFile.writeBytes(buffer.array())

            val waveform = WaveformProcessor.extractFromPcm(tempFile, targetSize = targetSize)
            assertEquals(targetSize, waveform.size)

            val quietBar1 = waveform[5]
            val loudBar1 = waveform[15]
            val quietBar2 = waveform[25]
            val loudBar2 = waveform[35]

            assertTrue("Loud bar ($loudBar1) should be significantly higher than quiet bar ($quietBar1)", loudBar1 > quietBar1 + 0.4f)
            assertTrue("Loud bar ($loudBar2) should be significantly higher than quiet bar ($quietBar2)", loudBar2 > quietBar2 + 0.4f)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `TEST 2 - Long Artifact simulation preserves dynamic contrast without flattening`() {
        val tempFile = File.createTempFile("long_artifact", ".pcm")
        try {
            // Simulate 100 windows representing long audio (~14 min)
            // Windows alternating between quiet pauses, moderate speech, and loud speech
            val windowCount = 100
            val samplesPerWindow = 1000 // Total 100,000 samples
            val buffer = ByteBuffer.allocate(windowCount * samplesPerWindow * 2).order(ByteOrder.LITTLE_ENDIAN)

            for (w in 0 until windowCount) {
                val sampleValue = when (w % 4) {
                    0 -> 500    // Pause / low background noise (~0.015)
                    1 -> 8000   // Soft speech (~0.24)
                    2 -> 24000  // Loud speech (~0.73)
                    else -> 12000 // Normal speech (~0.36)
                }
                repeat(samplesPerWindow) {
                    buffer.putShort(sampleValue.toShort())
                }
            }
            tempFile.writeBytes(buffer.array())

            val waveform = WaveformProcessor.extractFromPcm(tempFile, targetSize = 100)
            assertEquals(100, waveform.size)

            val maxAmp = waveform.maxOrNull() ?: 0f
            val minAmp = waveform.minOrNull() ?: 0f
            val dynamicRange = maxAmp - minAmp

            assertTrue("Waveform should have wide dynamic range (max: $maxAmp, min: $minAmp, range: $dynamicRange)", dynamicRange > 0.4f)
            assertTrue("Quiet pause bars should remain low (< 0.3f), got ${waveform[0]}", waveform[0] < 0.3f)
            assertTrue("Loud speech bars should reach near 1.0f, got ${waveform[2]}", waveform[2] > 0.9f)
            
            // Confirm waveform does NOT flatten into 0.93..0.98 range
            val highCount = waveform.count { it > 0.9f }
            assertTrue("High bars should only represent loud speech windows, got $highCount out of 100", highCount < 40)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `TEST 3 - Silence remains low and does not scale up to high amplitude`() {
        val tempFile = File.createTempFile("silence_test", ".pcm")
        try {
            val totalSamples = 10_000
            val buffer = ByteBuffer.allocate(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(totalSamples) {
                buffer.putShort(50) // Very low amplitude (near silence)
            }
            tempFile.writeBytes(buffer.array())

            val waveform = WaveformProcessor.extractFromPcm(tempFile, targetSize = 100)
            assertEquals(100, waveform.size)

            waveform.forEach { amp ->
                assertTrue("Silent waveform bar should remain low (<= 0.25f), got $amp", amp <= 0.25f)
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `TEST 4 - Constant loud signal remains consistently high`() {
        val tempFile = File.createTempFile("constant_loud", ".pcm")
        try {
            val totalSamples = 10_000
            val buffer = ByteBuffer.allocate(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until totalSamples) {
                buffer.putShort(28000) // High amplitude signal
            }
            tempFile.writeBytes(buffer.array())

            val waveform = WaveformProcessor.extractFromPcm(tempFile, targetSize = 100)
            assertEquals(100, waveform.size)

            waveform.forEach { amp ->
                assertTrue("Constant loud signal should yield high bar value (>= 0.8f), got $amp", amp >= 0.8f)
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `TEST 5 - Dynamic range preservation verifies max minus min amplitude`() {
        val rawAmplitudes = listOf(0.01f, 0.05f, 0.8f, 0.2f, 0.9f, 0.02f)
        val processed = WaveformProcessor.process(rawAmplitudes, targetSize = 6)

        val maxAmp = processed.maxOrNull() ?: 0f
        val minAmp = processed.minOrNull() ?: 0f
        val range = maxAmp - minAmp

        assertTrue("Dynamic range ($range) should be > 0.4f", range > 0.4f)
    }
}
