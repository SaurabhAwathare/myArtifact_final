package com.saurabh.artifact.audio

import android.app.PendingIntent
import android.content.Intent
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MediaItem
import androidx.media3.session.DefaultMediaNotificationProvider
import com.saurabh.artifact.MainActivity
import com.saurabh.artifact.util.NotificationHelper
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import dagger.hilt.android.AndroidEntryPoint

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.repository.PlayableArtifactRepository
import com.saurabh.artifact.repository.EngagementRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var playableArtifactRepository: dagger.Lazy<PlayableArtifactRepository>
    @Inject lateinit var engagementRepository: dagger.Lazy<EngagementRepository>
    @Inject lateinit var settingsDataStore: PlaybackSettingsDataStore

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private lateinit var attributionContext: android.content.Context

    companion object {
        private val ARTIFACT_AUDIO_ATTRIBUTES = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
            .build()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        ArtifactLogger.start(DiagnosticCategory.PLAYER, "SERVICE_CREATE")
        
        attributionContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            createAttributionContext("media_playback")
        } else {
            this
        }
        
        // Ensure the service is recognized as a foreground-capable media service
        // Use 'this' instead of 'attributionContext' for notification provider 
        // to avoid potential system-level attribution issues during early init.
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(NotificationHelper.CHANNEL_ID_PLAYBACK)
            .setChannelName(com.saurabh.artifact.R.string.playback_channel_name)
            .build()
        setMediaNotificationProvider(notificationProvider)
        
        initializeSession()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun initializeSession() {
        ArtifactLogger.d(DiagnosticCategory.PLAYER, "SESSION_INIT_STARTED")
        
        val dataSourceFactory = SmartDataSourceFactory(attributionContext)
        
        // Optimized buffering for network resilience
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000, // Min buffer 30s
                60_000, // Max buffer 60s
                2_500,  // Buffer for playback 2.5s
                5_000,   // Buffer for playback after rebuffer 5s
            )
            .build()

        val bandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(this)
        bandwidthMeter.addEventListener(Handler(Looper.getMainLooper())) { elapsedMs, bytesTransferred, bitrateEstimate ->
            ArtifactLogger.v(
                DiagnosticCategory.NETWORK,
                "BANDWIDTH_SAMPLE",
                mapOf(
                    "elapsedMs" to elapsedMs,
                    "bytes" to bytesTransferred,
                    "bitrate" to bitrateEstimate,
                )
            )
        }

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(dataSourceFactory)
            )
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setAudioAttributes(ARTIFACT_AUDIO_ATTRIBUTES, true)
            .setHandleAudioBecomingNoisy(true) // Pause on headphone unplug
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()

        // Wait for basic metadata to be ready before attaching to session
        player.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                // Redact user-generated title from logs even in debug
                ArtifactLogger.v(DiagnosticCategory.PLAYER, "METADATA_UPDATED", mapOf("hasTitle" to (mediaMetadata.title != null)))
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val artifact = player.currentMediaItem?.mediaId ?: "unknown"
                val pos = player.currentPosition
                
                when (playbackState) {
                    Player.STATE_READY -> {
                        ArtifactLogger.i(DiagnosticCategory.PLAYER, "STATE_READY", mapOf(
                            "artifactId" to artifact,
                            "pos" to pos,
                            "playWhenReady" to player.playWhenReady
                        ))
                        if (player.playWhenReady) {
                            ArtifactLogger.i(DiagnosticCategory.PLAYER, "PLAYBACK_STARTED", mapOf("artifactId" to artifact))
                        }
                    }
                    Player.STATE_ENDED -> {
                        ArtifactLogger.i(DiagnosticCategory.PLAYER, "PLAYBACK_COMPLETED", mapOf("artifactId" to artifact))
                    }
                    Player.STATE_IDLE -> {
                        ArtifactLogger.i(DiagnosticCategory.PLAYER, "STATE_IDLE", mapOf("artifactId" to artifact))
                    }
                    Player.STATE_BUFFERING -> {
                        ArtifactLogger.d(DiagnosticCategory.PLAYER, "PLAYBACK_BUFFERING", mapOf(
                            "artifactId" to artifact,
                            "pos" to pos
                        ))
                    }
                }
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                ArtifactLogger.v(DiagnosticCategory.PLAYER, "LOADING_CHANGED", mapOf(
                    "isLoading" to isLoading,
                    "pos" to player.currentPosition
                ))
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val artifact = player.currentMediaItem?.mediaId ?: "unknown"
                if (!playWhenReady) {
                    ArtifactLogger.i(DiagnosticCategory.PLAYER, "PLAYBACK_PAUSED", mapOf("artifactId" to artifact))
                } else if (player.playbackState == Player.STATE_READY) {
                    ArtifactLogger.i(DiagnosticCategory.PLAYER, "PLAYBACK_STARTED", mapOf("artifactId" to artifact))
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val artifact = player.currentMediaItem?.mediaId ?: "unknown"
                ArtifactLogger.e(DiagnosticCategory.PLAYER, "PLAYBACK_FAILED", mapOf("artifactId" to artifact), error)
            }
            
            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                ArtifactLogger.i(DiagnosticCategory.PLAYER, "PLAYBACK_SPEED_CHANGED", mapOf("speed" to playbackParameters.speed))
            }
        })

        val callback = object : MediaSession.Callback {
            @UnstableApi
            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: android.os.Bundle,
            ): ListenableFuture<SessionResult> {
                if (customCommand.customAction == "SET_SKIP_SILENCE") {
                    val enabled = args.getBoolean("enabled")
                    (session.player as? ExoPlayer)?.skipSilenceEnabled = enabled
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }

            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): MediaSession.ConnectionResult {
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand("SET_SKIP_SILENCE", android.os.Bundle.EMPTY))
                    .build()
                
                val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)

                if (session.isMediaNotificationController(controller)) {
                    val seekBackButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                        .setDisplayName("Seek Back 10s")
                        .setCustomIconResId(android.R.drawable.ic_media_rew)
                        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                        .setSlots(CommandButton.SLOT_BACK)
                        .build()
                    val seekForwardButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                        .setDisplayName("Seek Forward 10s")
                        .setCustomIconResId(android.R.drawable.ic_media_ff)
                        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                        .setSlots(CommandButton.SLOT_FORWARD)
                        .build()
                    
                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .add(Player.COMMAND_SEEK_BACK)
                        .add(Player.COMMAND_SEEK_FORWARD)
                        .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .remove(Player.COMMAND_SEEK_TO_NEXT)
                        .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .build()
                    
                    builder.setMediaButtonPreferences(listOf(seekBackButton, seekForwardButton))
                        .setAvailablePlayerCommands(playerCommands)
                }

                return builder.build()
            }

            @UnstableApi
            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                isForPlayback: Boolean,
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val completer = com.google.common.util.concurrent.SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                val startTime = android.os.SystemClock.elapsedRealtime()
                
                serviceScope.launch {
                    try {
                        val lastIds = settingsDataStore.currentQueueIds.first()
                        val lastIndex = settingsDataStore.currentQueueIndex.first()
                        val lastArtifactId = settingsDataStore.lastArtifactId.first()

                        val idsToFetch = if (lastIds.isNotEmpty()) {
                            lastIds
                        } else if (lastArtifactId != null) {
                            listOf(lastArtifactId)
                        } else {
                            emptyList()
                        }

                        val artifacts = if (idsToFetch.isNotEmpty()) {
                            playableArtifactRepository.get().resolveArtifactsByIds(idsToFetch).getOrDefault(emptyList())
                        } else {
                            emptyList()
                        }

                        if (artifacts.isNotEmpty()) {
                            val mediaItems = artifacts.map { createMediaItem(it) }
                            val currentArtifact = artifacts.getOrNull(lastIndex) ?: artifacts.first()
                            val pos = engagementRepository.get().getEngagement(currentArtifact.id).getOrNull()?.lastPositionMs ?: 0L
                            
                            val result = MediaSession.MediaItemsWithStartPosition(
                                mediaItems,
                                lastIndex,
                                pos,
                            )
                            val duration = android.os.SystemClock.elapsedRealtime() - startTime
                            ArtifactLogger.i(DiagnosticCategory.PLAYER, "RESUMPTION_SUCCESS", mapOf(LogKeys.DURATION_MS to duration, "count" to artifacts.size))
                            completer.set(result)
                        } else {
                            ArtifactLogger.w(DiagnosticCategory.PLAYER, "RESUMPTION_NO_ITEMS")
                            completer.setException(Exception("No items to resume"))
                        }
                    } catch (e: Exception) {
                        ArtifactLogger.e(DiagnosticCategory.PLAYER, "RESUMPTION_FAILED", throwable = e)
                        completer.setException(e)
                    }
                }
                
                return completer
            }

            override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
                super.onPostConnect(session, controller)
                ArtifactLogger.d(DiagnosticCategory.PLAYER, "CONTROLLER_CONNECTED", mapOf("packageName" to controller.packageName))
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(callback)
            .setSessionActivity(createSessionActivityPendingIntent())
            .build()
    }

    private fun createMediaItem(artifact: com.saurabh.artifact.model.Artifact): MediaItem {
        val uri = artifact.audioUrl.toUri()
            .buildUpon()
            .appendQueryParameter("artifact_id", artifact.id)
            .appendQueryParameter("encrypted", artifact.isEncrypted.toString())
            .build()

        val metadata = MediaMetadata.Builder()
            .setTitle(artifact.title)
            .setArtist(artifact.author.name)
            .setAlbumTitle("Artifacts")
            .setGenre(artifact.emotion)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(
                android.os.Bundle().apply {
                    putString("author_sigil", artifact.author.sigil)
                    putString("sigil_seed", artifact.author.sigilSeed) // Canonical identity key
                    
                    /**
                     * TODO: Retire "avatar_seed" once all external controllers (Wear OS, Auto) 
                     * have been updated to consume "sigil_seed". 
                     * Status: Deprecated for backward compatibility.
                     */
                    putString("avatar_seed", artifact.author.sigilSeed) 
                }
            )
            .build()

        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(artifact.id)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun createSessionActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if ((player == null) || (!player.playWhenReady) || (player.mediaItemCount == 0) || (player.playbackState == Player.STATE_IDLE)) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        ArtifactLogger.i(DiagnosticCategory.PLAYER, "SERVICE_DESTROY_INVOKED")
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
