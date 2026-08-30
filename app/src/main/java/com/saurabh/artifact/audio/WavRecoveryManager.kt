package com.saurabh.artifact.audio

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Handles recovery of "orphaned" WAV recordings that were not properly closed
 * due to app crashes or system-level failures.
 * 
 * Uses WavHeaderUtils for consistent header generation and validation.
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
        
        // A valid WAV must be at least 44 bytes (header only)
        if (file.length() < WavHeaderUtils.HEADER_SIZE) {
            Log.w("WavRecoveryManager", "File too small for WAV: ${file.length()} bytes")
            return RecoveryResult.CORRUPTED
        }

        return try {
            val actualSize = file.length()
            val hasValidHeader = checkWavHeaderValidity(file)

            if (hasValidHeader) {
                // Even if header is valid, we might want to log if DB checkpoint is significantly behind.
                lastDurableBytes?.let {
                    val audioBytes = actualSize - WavHeaderUtils.HEADER_SIZE
                    if (it < audioBytes) {
                        Log.d("WavRecoveryManager", "Recovery drift detected: File has ${audioBytes - it} bytes more than last checkpoint.")
                    }
                }
                RecoveryResult.FULLY_RECOVERED
            } else {
                // Determine the "safe" size to recover based on physical file size.
                // PCM 16-bit requires 2-byte alignment for the data chunk.
                val audioDataLength = (actualSize - WavHeaderUtils.HEADER_SIZE).coerceAtLeast(0)
                val alignedAudioLength = (audioDataLength / 2) * 2
                val targetSize = alignedAudioLength + WavHeaderUtils.HEADER_SIZE

                repairHeader(file, targetSize)
                
                if (targetSize < actualSize) {
                    Log.i("WavRecoveryManager", "Aligned ${file.name} to $targetSize bytes (Trimmed incomplete sample)")
                    RecoveryResult.TRUNCATED 
                } else {
                    Log.i("WavRecoveryManager", "Repaired header for ${file.name} (Size: $targetSize)")
                    RecoveryResult.REPAIRED
                }
            }
        } catch (e: Exception) {
            Log.e("WavRecoveryManager", "Recovery failed for ${file.name}", e)
            RecoveryResult.CORRUPTED
        }
    }

    private fun checkWavHeaderValidity(file: File): Boolean {
        return RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(WavHeaderUtils.HEADER_SIZE)
            raf.readFully(header)
            WavHeaderUtils.isValidHeader(header, file.length())
        }
    }

    private fun repairHeader(file: File, targetSize: Long) {
        val totalAudioLen = targetSize - WavHeaderUtils.HEADER_SIZE
        val header = WavHeaderUtils.createHeader(totalAudioLen, sampleRate, channels, bitsPerSample)

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
            
            // If we are truncating, set the length explicitly to ensure OS removes garbage tail
            if (file.length() > targetSize) {
                raf.setLength(targetSize)
            }
            raf.fd.sync()
        }
    }
}
