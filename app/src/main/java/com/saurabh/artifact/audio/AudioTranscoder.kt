package com.saurabh.artifact.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A production-grade audio transcoder that converts raw PCM (WAV) to AAC/M4A.
 * Uses standard Android MediaCodec and MediaMuxer APIs.
 */
@Singleton
class AudioTranscoder @Inject constructor() {

    companion object {
        private const val TAG = "AudioTranscoder"
        private const val TIMEOUT_US = 10000L
        private const val WAV_HEADER_SIZE = 44
    }

    /**
     * Transcodes a WAV file to an M4A file with AAC encoding.
     * Assumes 44100Hz, Mono, 16-bit PCM input (Artifact's standard).
     */
    fun transcodeWavToAac(input: File, output: File) {
        if (!input.exists()) throw java.io.FileNotFoundException("Input file not found: ${input.absolutePath}")
        
        val sampleRate = 44100
        val channels = 1
        val bitrate = 128000

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            FileInputStream(input).use { fis ->
                fis.skip(WAV_HEADER_SIZE.toLong())

                val buffer = ByteArray(4096)
                val info = MediaCodec.BufferInfo()
                var isEof = false
                var presentationTimeUs = 0L

                while (true) {
                    if (!isEof) {
                        val inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = encoder.getInputBuffer(inputBufferIndex)!!
                            inputBuffer.clear()
                            val read = fis.read(buffer)
                            if (read > 0) {
                                inputBuffer.put(buffer, 0, read)
                                encoder.queueInputBuffer(inputBufferIndex, 0, read, presentationTimeUs, 0)
                                // Calculate next presentation time based on mono 16-bit samples
                                presentationTimeUs += (read * 1000000L) / (sampleRate * channels * 2)
                            } else {
                                isEof = true
                                encoder.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                        }
                    }

                    val outputBufferIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (outputBufferIndex >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            // This is codec configuration data, not actual audio data.
                            // Some encoders might provide this separately.
                            info.size = 0
                        }

                        if (info.size != 0 && muxerStarted) {
                            val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)!!
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, info)
                        }

                        encoder.releaseOutputBuffer(outputBufferIndex, false)

                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.d(TAG, "Transcoding reached end of stream")
                            break
                        }
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw IllegalStateException("Output format changed twice")
                        val newFormat = encoder.outputFormat
                        trackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                        Log.d(TAG, "Muxer started with track index $trackIndex")
                    } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // Just loop if we're waiting for encoder
                        Thread.sleep(10)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcoding failed", e)
            throw e
        } finally {
            try {
                encoder.stop()
            } catch (_: Exception) {}
            encoder.release()
            try {
                muxer?.stop()
            } catch (_: Exception) {}
            muxer?.release()
        }
    }
}
