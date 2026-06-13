package com.saurabh.artifact.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

enum class RecordingMode {
    AAC_HIGH_BITRATE,
    WAV_LOSSLESS
}

/**
 * A production-grade audio recording engine supporting multiple formats.
 * Wraps MediaRecorder for AAC and WavRecorder for lossless PCM.
 */
class AudioRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var wavRecorder: WavRecorder? = null
    private var currentMode: RecordingMode = RecordingMode.WAV_LOSSLESS
    private var isRecording = false

    /**
     * Optional callbacks for hardware-level events.
     */
    var onError: ((Int, Int) -> Unit)? = null
    var onInfo: ((Int, Int) -> Unit)? = null
    var onStorageError: ((Exception) -> Unit)? = null

    /**
     * Configures and starts audio capture in the specified mode.
     * @param outputFile The destination file.
     * @param mode The recording format (AAC or WAV).
     */
    fun start(
        outputFile: File,
        mode: RecordingMode = RecordingMode.WAV_LOSSLESS,
        onDurableSync: ((Long) -> Unit)? = null
    ) {
        if (isRecording && mode == currentMode) {
            Log.w("AudioRecorder", "Start called while already recording in same mode. Ignoring.")
            return
        }

        currentMode = mode
        outputFile.parentFile?.mkdirs()

        try {
            when (mode) {
                RecordingMode.AAC_HIGH_BITRATE -> startAAC(outputFile)
                RecordingMode.WAV_LOSSLESS -> startWAV(outputFile, onDurableSync)
            }
            isRecording = true
            Log.d("AudioRecorder", "Recording started ($mode): ${outputFile.name}")
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Critical failure in start ($mode): ${e.message}", e)
            stop()
            if (outputFile.exists() && mode == RecordingMode.AAC_HIGH_BITRATE) outputFile.delete()
            throw e
        }
    }

    private fun startAAC(outputFile: File) {
        val attributionContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.createAttributionContext("audio_recording")
        } else {
            context
        }

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(attributionContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        
        mediaRecorder = recorder
        recorder.apply {
            setOnErrorListener { _, what, extra ->
                Log.e("AudioRecorder", "MediaRecorder error: what=$what, extra=$extra")
                onError?.invoke(what, extra)
            }
            setOnInfoListener { _, what, extra ->
                Log.d("AudioRecorder", "MediaRecorder info: what=$what, extra=$extra")
                onInfo?.invoke(what, extra)
            }
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000) // High quality AAC
            setOutputFile(outputFile.absolutePath)

            // Set a native file size limit based on available space (with 20MB safety buffer)
            // This is a defense-in-depth measure for AAC.
            val stats = android.os.StatFs(context.filesDir.absolutePath)
            val availableBytes = stats.availableBlocksLong * stats.blockSizeLong
            val safetyBuffer = 20 * 1024 * 1024L
            if (availableBytes > safetyBuffer) {
                setMaxFileSize(availableBytes - safetyBuffer)
            }

            prepare()
            start()
        }
    }

    private fun startWAV(outputFile: File, onDurableSync: ((Long) -> Unit)? = null) {
        wavRecorder = WavRecorder(context, outputFile, onDurableSync = onDurableSync).apply {
            onStorageError = { this@AudioRecorder.onStorageError?.invoke(it) }
            start()
        }
        isRecording = true
    }

    fun pause() {
        if (!isRecording) return
        try {
            if (currentMode == RecordingMode.AAC_HIGH_BITRATE) {
                mediaRecorder?.pause()
            } else {
                wavRecorder?.pause()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Failed to pause: ${e.message}")
        }
    }

    fun resume() {
        if (!isRecording) return
        try {
            if (currentMode == RecordingMode.AAC_HIGH_BITRATE) {
                mediaRecorder?.resume()
            } else {
                wavRecorder?.start(isResume = true)
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Failed to resume: ${e.message}")
        }
    }

    fun stop() {
        if (!isRecording) return
        
        try {
            when (currentMode) {
                RecordingMode.AAC_HIGH_BITRATE -> {
                    mediaRecorder?.apply {
                        try { stop() } catch (_: Exception) {}
                        reset()
                        release()
                    }
                    mediaRecorder = null
                }
                RecordingMode.WAV_LOSSLESS -> {
                    wavRecorder?.stop()
                    // We don't null out wavRecorder here if we want to reuse it, 
                    // but since startWAV creates a new one, we should release it.
                    wavRecorder?.release()
                    wavRecorder = null
                }
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error during stop: ${e.message}")
        } finally {
            isRecording = false
        }
    }

    /**
     * Releases all underlying resources.
     */
    fun release() {
        stop()
        onError = null
        onInfo = null
    }

    val maxAmplitude: Int
        get() = when (currentMode) {
            RecordingMode.AAC_HIGH_BITRATE -> mediaRecorder?.maxAmplitude ?: 0
            RecordingMode.WAV_LOSSLESS -> wavRecorder?.maxAmplitude ?: 0
        }
}
