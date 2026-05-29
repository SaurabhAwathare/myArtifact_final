package com.saurabh.artifact.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

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
     * Configures and starts audio capture in the specified mode.
     * @param outputFile The destination file.
     * @param mode The recording format (AAC or WAV).
     */
    fun start(
        outputFile: File,
        mode: RecordingMode = RecordingMode.WAV_LOSSLESS,
        onDurableSync: ((Long) -> Unit)? = null
    ) {
        if (isRecording) {
            Log.w("AudioRecorder", "Start called while already recording. Ignoring.")
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
            if (outputFile.exists()) outputFile.delete()
            throw e
        }
    }

    private fun startAAC(outputFile: File) {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        
        mediaRecorder = recorder
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000) // High quality AAC
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
    }

    private fun startWAV(outputFile: File, onDurableSync: ((Long) -> Unit)? = null) {
        wavRecorder = WavRecorder(outputFile, onDurableSync = onDurableSync).apply {
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
                // WAV (AudioRecord) doesn't natively support pause in this implementation.
                // For now, we'll log it as a limitation or implement it via file segmenting.
                Log.w("AudioRecorder", "Pause not yet implemented for WAV mode.")
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
                Log.w("AudioRecorder", "Resume not yet implemented for WAV mode.")
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
                    wavRecorder = null
                }
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error during stop: ${e.message}")
        } finally {
            isRecording = false
        }
    }

    val maxAmplitude: Int
        get() = when (currentMode) {
            RecordingMode.AAC_HIGH_BITRATE -> mediaRecorder?.maxAmplitude ?: 0
            RecordingMode.WAV_LOSSLESS -> wavRecorder?.maxAmplitude ?: 0
        }

    fun validateOutputFile(file: File?): Boolean {
        if (file == null || !file.exists()) return false
        return file.length() > 1024
    }
}
