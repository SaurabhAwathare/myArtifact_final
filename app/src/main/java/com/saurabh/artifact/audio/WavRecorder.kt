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
import java.io.FileOutputStream
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
    private var isRecording = false
    
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
    private val audioChannel = Channel<ByteArray>(capacity = 100)

    // Durability Constants
    private val syncIntervalBytes = 5 * 1024 * 1024L // 5MB Durability Barrier
    private var totalBytesWritten = 0L
    private var bytesSinceLastSync = 0L

    private var _maxAmplitude = 0
    val maxAmplitude: Int get() {
        val amp = _maxAmplitude
        _maxAmplitude = 0 // Reset after read to simulate MediaRecorder behavior
        return amp
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) return

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
        bufferPool = BufferPool(minBufferSize)

        // Launch Producer: Capture Loop (Highest Priority, Dedicated Thread)
        captureJob = scope.launch(captureDispatcher + CoroutineName("AudioCapture")) {
            // Set thread priority to AUDIO to avoid preemption
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            captureAudioLoop(minBufferSize)
        }

        // Launch Consumer: Writer Loop (Background Priority)
        writerJob = scope.launch(CoroutineName("StorageWriter")) {
            writeAudioDataToFile()
        }
    }

    /**
     * High-frequency loop focused solely on draining the AudioRecord hardware buffer.
     * No blocking I/O allowed here. Uses BufferPool to reduce GC pressure.
     */
    private suspend fun captureAudioLoop(bufferSize: Int) {
        try {
            while (isRecording && yield().let { true }) {
                val data = bufferPool?.acquire() ?: ByteArray(bufferSize)
                val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                if (read > 0) {
                    // Send to channel. This will suspend ONLY if the 18-second safety buffer is full.
                    audioChannel.send(data)
                    calculateMaxAmplitude(data, read)
                } else {
                    bufferPool?.release(data)
                }
            }
        } catch (e: Exception) {
            Log.e("WavRecorder", "Capture loop error", e)
        } finally {
            audioChannel.close()
        }
    }

    /**
     * Background loop responsible for persistence and durability barriers.
     * Can survive transient storage stalls without affecting capture.
     */
    private suspend fun writeAudioDataToFile() {
        withContext(Dispatchers.IO) {
            val fos = FileOutputStream(outputFile)
            try {
                // Placeholder for WAV header (44 bytes)
                fos.write(ByteArray(44))
                totalBytesWritten = 0

                for (audioData in audioChannel) {
                    try {
                        fos.write(audioData)
                        val size = audioData.size.toLong()
                        totalBytesWritten += size
                        bytesSinceLastSync += size

                        // Periodic Hard Sync: Durability Barrier
                        if (bytesSinceLastSync >= syncIntervalBytes) {
                            Log.d("WavRecorder", "Durability Barrier: fsync() at $totalBytesWritten bytes")
                            fos.fd.sync() // BLOCKING HARD SYNC
                            bytesSinceLastSync = 0
                            
                            // Notify checkpoint manager (e.g. Database)
                            onDurableSync?.invoke(totalBytesWritten)
                        }
                    } finally {
                        bufferPool?.release(audioData)
                    }
                }
            } catch (e: Exception) {
                Log.e("WavRecorder", "Writer loop error", e)
            } finally {
                fos.flush()
                fos.fd.sync() // Ensure last bits are durable
                fos.close()
                updateWavHeader()
            }
        }
    }

    private fun calculateMaxAmplitude(data: ByteArray, size: Int) {
        // Assuming 16-bit PCM Little Endian
        val buffer = ByteBuffer.wrap(data, 0, size).order(ByteOrder.LITTLE_ENDIAN)
        var max = 0
        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 2) break
            val sample = kotlin.math.abs(buffer.short.toInt())
            if (sample > max) max = sample
        }
        _maxAmplitude = max
    }

    fun stop() {
        isRecording = false
        // The loops will terminate gracefully via isRecording flag and channel closing
        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                stop()
            }
            release()
        }
        audioRecord = null
    }

    private fun updateWavHeader() {
        if (!outputFile.exists()) return
        
        val totalAudioLen = outputFile.length() - 44
        if (totalAudioLen < 0) return

        val totalDataLen = totalAudioLen + 36
        val channels = if (channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2
        val byteRate = (sampleRate * channels * 16) / 8

        RandomAccessFile(outputFile, "rw").use { raf ->
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

            raf.seek(0)
            raf.write(header)
            raf.fd.sync() // Ensure header update is durable
        }
    }
}
