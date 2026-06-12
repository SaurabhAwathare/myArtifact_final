package com.saurabh.artifact.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Centralized utility for WAV/PCM header manipulation.
 * Ensures consistency between recording and recovery logic.
 */
object WavHeaderUtils {
    const val HEADER_SIZE = 44
    const val WAV_FORMAT_PCM = 1.toShort()

    /**
     * Generates a standard 44-byte WAV header for PCM data.
     */
    fun createHeader(
        audioDataLength: Long,
        sampleRate: Int = 44100,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val totalDataLen = audioDataLength + 36
        val byteRate = (sampleRate * channels * bitsPerSample) / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()

        val header = ByteArray(HEADER_SIZE)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk
        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalDataLen.toInt())
        buffer.put("WAVE".toByteArray())

        // fmt chunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(WAV_FORMAT_PCM)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign)
        buffer.putShort(bitsPerSample.toShort())

        // data chunk
        buffer.put("data".toByteArray())
        buffer.putInt(audioDataLength.toInt())

        return header
    }

    /**
     * Inspects a header and verifies if it matches the expected WAV structure and file size.
     */
    fun isValidHeader(
        header: ByteArray,
        fileLength: Long
    ): Boolean {
        if (header.size < HEADER_SIZE) return false
        
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        
        // Check RIFF and WAVE magic numbers
        val riff = ByteArray(4)
        buffer.get(riff)
        if (String(riff) != "RIFF") return false
        
        buffer.position(8)
        val wave = ByteArray(4)
        buffer.get(wave)
        if (String(wave) != "WAVE") return false

        // Check data size in header
        buffer.position(40)
        val dataSizeInHeader = buffer.getInt().toLong() and 0xFFFFFFFFL
        
        return dataSizeInHeader == (fileLength - HEADER_SIZE)
    }

    /**
     * Calculates the duration of audio data based on header parameters.
     */
    fun calculateDurationMs(audioDataLength: Long, sampleRate: Int, channels: Int, bitsPerSample: Int): Long {
        val byteRate = (sampleRate * channels * bitsPerSample) / 8
        if (byteRate == 0) return 0
        return (audioDataLength * 1000) / byteRate
    }
}
