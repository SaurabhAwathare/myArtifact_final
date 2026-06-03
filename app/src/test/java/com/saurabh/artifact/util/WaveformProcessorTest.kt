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
    fun `process downsamples correctly using max peaks`() {
        // [0.1, 0.9, 0.1, 0.1] -> target size 2
        // Chunk 1: [0.1, 0.9] -> Max is 0.9
        // Chunk 2: [0.1, 0.1] -> Max is 0.1
        val raw = listOf(0.1f, 0.9f, 0.1f, 0.1f)
        val processed = WaveformProcessor.process(raw, targetSize = 2)
        
        assertEquals(2, processed.size)
        // Since we use Max, the first bar should be significantly higher than the second
        assertTrue(processed[0] > processed[1])
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
            
            // Check that we got meaningful data (above minimum)
            waveform.forEach { amp ->
                assertTrue(amp >= 0.1f)
            }
        } finally {
            tempFile.delete()
        }
    }
}
