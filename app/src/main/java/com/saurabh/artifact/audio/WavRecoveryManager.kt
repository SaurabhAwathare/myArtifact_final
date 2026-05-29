package com.saurabh.artifact.audio

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Handles recovery of "orphaned" WAV recordings that were not properly closed
 * due to app crashes or system-level failures.
 */
class WavRecoveryManager(
    private val sampleRate: Int = 44100,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16
) {
    enum class RecoveryResult {
        FULLY_RECOVERED,    // File was already valid
        REPAIRED,           // Header was missing/invalid but fixed using file size
        TRUNCATED,          // File was repaired but some data was lost
        CORRUPTED,          // File could not be recovered
        NOT_FOUND           // File doesn't exist
    }

    /**
     * Inspects a WAV file and repairs the header if it's orphaned.
     * 
     * @param file The potential WAV file to recover.
     * @param lastDurableBytes The byte count from the last successful checkpoint (optional).
     */
    fun recover(file: File, lastDurableBytes: Long? = null): RecoveryResult {
        if (!file.exists()) return RecoveryResult.NOT_FOUND
        if (file.length() < 44) return RecoveryResult.CORRUPTED

        return try {
            val actualSize = file.length()
            val hasValidHeader = checkWavHeaderValidity(file)

            if (hasValidHeader) {
                RecoveryResult.FULLY_RECOVERED
            } else {
                // Determine the "safe" size to recover.
                // If we have a checkpoint, we can use it to ensure we don't recover partial blocks.
                val targetSize = lastDurableBytes?.let { 
                    if (it + 44 <= actualSize) it + 44 else actualSize 
                } ?: actualSize

                repairHeader(file, targetSize)
                
                if (targetSize < actualSize) RecoveryResult.TRUNCATED else RecoveryResult.REPAIRED
            }
        } catch (e: Exception) {
            Log.e("WavRecoveryManager", "Recovery failed for ${file.name}", e)
            RecoveryResult.CORRUPTED
        }
    }

    private fun checkWavHeaderValidity(file: File): Boolean {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)
            
            // Check RIFF and WAVE magic numbers
            val isRiff = header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() && 
                         header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()
            val isWave = header[8] == 'W'.code.toByte() && header[9] == 'A'.code.toByte() && 
                         header[10] == 'V'.code.toByte() && header[11] == 'E'.code.toByte()
            
            if (!isRiff || !isWave) return false

            // Check if the data size in header matches actual file size
            val dataSizeInHeader = readIntLittleEndian(header, 40)
            return dataSizeInHeader.toLong() == (file.length() - 44)
        }
    }

    private fun repairHeader(file: File, targetSize: Long) {
        val totalAudioLen = targetSize - 44
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitsPerSample) / 8

        RandomAccessFile(file, "rw").use { raf ->
            val header = ByteArray(44)

            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = (totalDataLen shr 8 and 0xff).toByte()
            header[6] = (totalDataLen shr 16 and 0xff).toByte()
            header[7] = (totalDataLen shr 24 and 0xff).toByte()
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1 // PCM
            header[21] = 0
            header[22] = channels.toByte()
            header[23] = 0
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = (sampleRate shr 8 and 0xff).toByte()
            header[26] = (sampleRate shr 16 and 0xff).toByte()
            header[27] = (sampleRate shr 24 and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = (byteRate shr 8 and 0xff).toByte()
            header[30] = (byteRate shr 16 and 0xff).toByte()
            header[31] = (byteRate shr 24 and 0xff).toByte()
            header[32] = (channels * bitsPerSample / 8).toByte()
            header[33] = 0
            header[34] = bitsPerSample.toByte()
            header[35] = 0
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (totalAudioLen and 0xff).toByte()
            header[41] = (totalAudioLen shr 8 and 0xff).toByte()
            header[42] = (totalAudioLen shr 16 and 0xff).toByte()
            header[43] = (totalAudioLen shr 24 and 0xff).toByte()

            raf.seek(0)
            raf.write(header)
            
            // If we are truncating, set the length
            if (file.length() > targetSize) {
                raf.setLength(targetSize)
            }
            raf.getFD().sync()
        }
    }

    private fun readIntLittleEndian(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xff) or
               ((data[offset + 1].toInt() and 0xff) shl 8) or
               ((data[offset + 2].toInt() and 0xff) shl 16) or
               ((data[offset + 3].toInt() and 0xff) shl 24)
    }
}
