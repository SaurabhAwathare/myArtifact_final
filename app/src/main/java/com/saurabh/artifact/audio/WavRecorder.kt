package com.saurabh.artifact.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.os.Process
import com.saurabh.artifact.util.BufferPool
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * A robust lossless PCM WAV recorder using AudioRecord.
 * 
 * Uses an asynchronous producer-consumer pipeline (via Kotlin Channels) 
 * to decouple high-priority audio capture from potentially slow disk I/O.
 * Provides durability barriers to minimize data loss during system crashes.
 */
class WavRecorder(
    private val outputFile: File,
    private val sampleRate: Int = 44100,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val onDurableSync: ((Long) -> Unit)? = null,
) {
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false
    @Volatile
    private var isPaused = false
    
    // Coroutine control
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null
    private var writerJob: Job? = null

    // Dedicated high-priority thread for audio capture
    private val captureDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AudioCaptureThread")
    }.asCoroutineDispatcher()

    private var bufferPool: BufferPool? = null

    /**
     * Internal channel acting as a pressure valve for storage stalls.
     * Capacity of 100 buffers (~180ms each) provides ~18 seconds of safety buffer.
     */
    private val audioChannel = Channel<AudioBuffer>(capacity = 100)

    private data class AudioBuffer(val data: ByteArray, val size: Int)

    // Durability Constants
    private val syncIntervalBytes = 1 * 1024 * 1024L // 1MB Durability Barrier (reduced from 5MB)
    private var totalBytesWritten = 0L
    private var bytesSinceLastSync = 0L

    private var _maxAmplitude = 0
    val maxAmplitude: Int get() {
        val amp = _maxAmplitude
        _maxAmplitude = 0 // Reset after read to simulate MediaRecorder behavior
        return amp
    }

    @SuppressLint("MissingPermission")
    fun start(isResume: Boolean = false) {
        if (isRecording && !isPaused) return

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e("WavRecorder", "Invalid AudioRecord parameters")
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("WavRecorder", "AudioRecord initialization failed")
            return
        }

        audioRecord?.startRecording()
        isRecording = true
        isPaused = false
        
        if (bufferPool == null) {
            bufferPool = BufferPool(minBufferSize)
        }

        // Launch Producer: Capture Loop (Highest Priority, Dedicated Thread)
        captureJob = scope.launch(captureDispatcher + CoroutineName("AudioCapture")) {
            // Set thread priority to AUDIO to avoid preemption
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            captureAudioLoop(minBufferSize)
        }

        // Only launch writer if it's not already running (e.g., during resume)
        if (writerJob == null || !writerJob!!.isActive) {
            writerJob = scope.launch(CoroutineName("StorageWriter")) {
                writeAudioDataToFile(isResume)
            }
        }
    }

    /**
     * High-frequency loop focused solely on draining the AudioRecord hardware buffer.
     * No blocking I/O allowed here. Uses BufferPool to reduce GC pressure.
     */
    private suspend fun captureAudioLoop(bufferSize: Int) {
        try {
            while (isRecording && !isPaused && currentCoroutineContext().isActive) {
                val data = bufferPool?.acquire() ?: ByteArray(bufferSize)
                val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                if (read > 0) {
                    // Send to channel. This will suspend ONLY if the 18-second safety buffer is full.
                    audioChannel.send(AudioBuffer(data, read))
                    calculateMaxAmplitude(data, read)
                } else {
                    bufferPool?.release(data)
                }
            }
        } catch (e: Exception) {
            Log.e("WavRecorder", "Capture loop error", e)
        } finally {
            // Only close channel if we are truly stopping, not just pausing
            if (!isPaused) {
                audioChannel.close()
            }
        }
    }

    /**
     * Background loop responsible for persistence and durability barriers.
     * Can survive transient storage stalls without affecting capture.
     */
    private suspend fun writeAudioDataToFile(append: Boolean = false) {
        withContext(Dispatchers.IO) {
            RandomAccessFile(outputFile, "rw").use { raf ->
                try {
                    if (!append) {
                        // Initial valid WAV header (0 length)
                        writeWavHeader(raf, 0)
                        totalBytesWritten = 0
                    } else {
                        // If appending, move to the end of the file
                        raf.seek(outputFile.length())
                        totalBytesWritten = maxOf(0, outputFile.length() - 44)
                    }

                    for (audioBuffer in audioChannel) {
                        try {
                            raf.write(audioBuffer.data, 0, audioBuffer.size)
                            val size = audioBuffer.size.toLong()
                            totalBytesWritten += size
                            bytesSinceLastSync += size

                            // Periodic Hard Sync: Durability Barrier
                            if (bytesSinceLastSync >= syncIntervalBytes) {
                                Log.d("WavRecorder", "Durability Barrier: fsync() and header update at $totalBytesWritten bytes")
                                raf.fd.sync() // BLOCKING HARD SYNC
                                
                                // Update header proactively
                                val currentPos = raf.filePointer
                                writeWavHeader(raf, totalBytesWritten)
                                raf.seek(currentPos)
                                raf.fd.sync()
                                
                                bytesSinceLastSync = 0
                                
                                // Notify checkpoint manager (e.g. Database)
                                onDurableSync?.invoke(totalBytesWritten)
                            }
                        } finally {
                            bufferPool?.release(audioBuffer.data)
                        }
                        
                        if (isPaused) break // Exit loop if paused to flush current state
                    }
                } catch (e: Exception) {
                    Log.e("WavRecorder", "Writer loop error", e)
                } finally {
                    writeWavHeader(raf, totalBytesWritten)
                    raf.fd.sync() // Ensure last bits are durable
                }
            }
        }
    }

    private fun writeWavHeader(raf: RandomAccessFile, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val channels = if (channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2
        val byteRate = (sampleRate * channels * 16) / 8
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte() // RIFF/WAVE header
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
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
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
        header[32] = (channels * 16 / 8).toByte() // block align
        header[33] = 0
        header[34] = 16 // bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()

        val currentPos = raf.filePointer
        raf.seek(0)
        raf.write(header)
        raf.seek(currentPos)
    }

    private fun calculateMaxAmplitude(data: ByteArray, size: Int) {
        // Assuming 16-bit PCM Little Endian
        val buffer = ByteBuffer.wrap(data, 0, size).order(ByteOrder.LITTLE_ENDIAN)
        var currentMax = _maxAmplitude
        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 2) break
            val sample = kotlin.math.abs(buffer.short.toInt())
            if (sample > currentMax) currentMax = sample
        }
        _maxAmplitude = currentMax
    }

    fun pause() {
        if (!isRecording || isPaused) return
        isPaused = true
        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                stop()
            }
            release()
        }
        audioRecord = null
        // Loops will terminate and flush via isPaused flag
    }

    fun stop() {
        isRecording = false
        isPaused = false
        // The loops will terminate gracefully via isRecording flag and channel closing
        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                stop()
            }
            release()
        }
        audioRecord = null
    }

}
