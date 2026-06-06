package com.saurabh.artifact.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages a secondary, lightweight player for ambient sounds or quick previews.
 * Unlike [PlaybackSessionManager], this does NOT connect to the MediaSession,
 * allowing it to play simultaneously with the main audio.
 */
@Singleton
class TransientPlayerManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: ExoPlayer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var releaseJob: Job? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d("TransientPlayer", "Focus lost ($focusChange). Stopping.")
                stop()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Lower volume for notifications/navigation
                player?.volume = 0.1f
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Restore volume
                player?.volume = 0.5f // Default ambient volume
            }
        }
    }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (!isPlaying) {
                scheduleRelease()
            } else {
                releaseJob?.cancel()
            }
        }
    }

    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(10.seconds) // Release after 10 seconds of inactivity to reclaim resources
            Log.d("TransientPlayer", "Inactivity timeout. Releasing player.")
            release()
        }
    }

    private fun initializePlayer(): ExoPlayer {
        releaseJob?.cancel()
        return player ?: ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                false // Do not handle audio focus automatically to allow mixing
            )
            .build().also {
                it.addListener(playerListener)
                player = it
            }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attr = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attr)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            
            audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    /**
     * Plays a transient audio file.
     * @param url The audio source URL or local path.
     * @param loop Whether the audio should loop (good for ambient textures).
     * @param volume Volume level (0.0 to 1.0). Default is 0.5 to keep it "ambient".
     */
    fun play(url: String, loop: Boolean = false, volume: Float = 0.5f) {
        if (!requestAudioFocus()) {
            Log.w("TransientPlayer", "Could not acquire focus for ambient audio")
            return
        }
        
        scope.launch {
            val p = initializePlayer()
            p.stop()
            p.clearMediaItems()
            
            val mediaItem = MediaItem.fromUri(url)
            p.setMediaItem(mediaItem)
            p.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            p.volume = volume
            p.prepare()
            p.play()
        }
    }

    fun stop() {
        abandonAudioFocus()
        scope.launch {
            player?.stop()
        }
    }

    fun release() {
        abandonAudioFocus()
        player?.removeListener(playerListener)
        player?.release()
        player = null
        scope.coroutineContext.cancelChildren()
    }
}
