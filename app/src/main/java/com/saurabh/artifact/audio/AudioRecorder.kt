package com.saurabh.artifact.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
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
            ArtifactLogger.w(DiagnosticCategory.RECORDER, "RECORDING_START_IGNORED", mapOf("reason" to "ALREADY_RECORDING"))
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
            ArtifactLogger.d(DiagnosticCategory.RECORDER, "RECORDING_STARTED", mapOf("mode" to mode.name, "file" to outputFile.name))
        } catch (e: Exception) {
            ArtifactLogger.e(DiagnosticCategory.RECORDER, "RECORDING_CRITICAL_FAILURE", mapOf("mode" to mode.name), e)
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
                ArtifactLogger.e(DiagnosticCategory.RECORDER, "MEDIA_RECORDER_ERROR", mapOf("what" to what, "extra" to extra))
                onError?.invoke(what, extra)
            }
            setOnInfoListener { _, what, extra ->
                ArtifactLogger.d(DiagnosticCategory.RECORDER, "MEDIA_RECORDER_INFO", mapOf("what" to what, "extra" to extra))
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
            ArtifactLogger.e(DiagnosticCategory.RECORDER, "RECORDING_PAUSE_FAILED", throwable = e)
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
            ArtifactLogger.e(DiagnosticCategory.RECORDER, "RECORDING_RESUME_FAILED", throwable = e)
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
            ArtifactLogger.e(DiagnosticCategory.RECORDER, "RECORDING_STOP_FAILED", throwable = e)
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
