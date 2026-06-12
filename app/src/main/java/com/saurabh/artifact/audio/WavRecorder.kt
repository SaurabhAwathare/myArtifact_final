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
    var onStorageError: ((Exception) -> Unit)? = null,
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
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AudioCaptureThread")
    }
    private val captureDispatcher = executor.asCoroutineDispatcher()

    private var bufferPool: BufferPool? = null

    /**
     * Internal channel acting as a pressure valve for storage stalls.
     * Capacity of 100 buffers (~180ms each) provides ~18 seconds of safety buffer.
     */
    private val audioChannel = Channel<AudioBuffer>(capacity = 100)

    private data class AudioBuffer(val data: ByteArray, val size: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as AudioBuffer

            if (!data.contentEquals(other.data)) return false
            if (size != other.size) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + size
            return result
        }
    }

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
                        val header = WavHeaderUtils.createHeader(0, sampleRate, if (channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2, 16)
                        raf.seek(0)
                        raf.write(header)
                        totalBytesWritten = 0
                    } else {
                        // If appending, move to the end of the file
                        raf.seek(outputFile.length())
                        totalBytesWritten = maxOf(0, outputFile.length() - WavHeaderUtils.HEADER_SIZE.toLong())
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
                                val header = WavHeaderUtils.createHeader(totalBytesWritten, sampleRate, if (channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2, 16)
                                raf.seek(0)
                                raf.write(header)
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
                    if (e is java.io.IOException) {
                        withContext(Dispatchers.Main) {
                            onStorageError?.invoke(e)
                        }
                    }
                } finally {
                    val header = WavHeaderUtils.createHeader(totalBytesWritten, sampleRate, if (channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2, 16)
                    val currentPos = raf.filePointer
                    raf.seek(0)
                    raf.write(header)
                    raf.seek(currentPos)
                    raf.fd.sync() // Ensure last bits are durable
                }
            }
        }
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

    /**
     * Fully releases all resources, including threads and scopes.
     * Should be called when the recorder is no longer needed.
     */
    fun release() {
        stop()
        scope.cancel()
        executor.shutdown()
        audioChannel.close()
    }

}
