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
                // Even if header is valid, we might want to truncate if DB checkpoint is much smaller.
                // But generally, we trust the file header if it's consistent with file size.
                RecoveryResult.FULLY_RECOVERED
            } else {
                // Determine the "safe" size to recover.
                // If we have a checkpoint, we use it to ensure we don't recover partial/corrupt blocks.
                val targetSize = lastDurableBytes?.let { 
                    val expectedTotal = it + WavHeaderUtils.HEADER_SIZE
                    if (expectedTotal <= actualSize) expectedTotal else actualSize 
                } ?: actualSize

                repairHeader(file, targetSize)
                
                if (targetSize < actualSize) {
                    Log.i("WavRecoveryManager", "Truncated ${file.name} to $targetSize bytes (Checkpointed: $lastDurableBytes)")
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
