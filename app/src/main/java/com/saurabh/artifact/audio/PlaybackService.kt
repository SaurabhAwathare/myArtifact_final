package com.saurabh.artifact.audio

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import com.google.common.collect.ImmutableList
import androidx.media3.session.DefaultMediaNotificationProvider
import com.saurabh.artifact.MainActivity
import dagger.hilt.android.AndroidEntryPoint

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.EngagementRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var artifactRepository: ArtifactRepository
    @Inject lateinit var engagementRepository: EngagementRepository
    @Inject lateinit var settingsDataStore: PlaybackSettingsDataStore

    private var mediaSession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.d("PlaybackService", "onCreate - Initializing MediaSession")
        
        // Ensure the service is recognized as a foreground-capable media service
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build())
        
        initializeSession()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun initializeSession() {
        Log.d("PlaybackService", "Initializing ExoPlayer and MediaSession")
        
        val dataSourceFactory = SmartDataSourceFactory(this)
        
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true) // Pause on headphone unplug
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()

        val callback = object : MediaLibrarySession.Callback {
            @UnstableApi
            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: android.os.Bundle
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
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand("SET_SKIP_SILENCE", android.os.Bundle.EMPTY))
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .build()
            }

            @Suppress("DEPRECATION")
            @UnstableApi
            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val completer = com.google.common.util.concurrent.SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                
                serviceScope.launch {
                    try {
                        val lastIds = settingsDataStore.currentQueueIds.first()
                        val lastIndex = settingsDataStore.currentQueueIndex.first()
                        val lastArtifactId = settingsDataStore.lastArtifactId.first()

                        val artifacts = if (lastIds.isNotEmpty()) {
                            lastIds.mapNotNull { artifactRepository.getArtifactById(it) }
                        } else if (lastArtifactId != null) {
                            listOfNotNull(artifactRepository.getArtifactById(lastArtifactId))
                        } else {
                            emptyList()
                        }

                        if (artifacts.isNotEmpty()) {
                            val mediaItems = artifacts.map { createMediaItem(it) }
                            val currentArtifact = artifacts.getOrNull(lastIndex) ?: artifacts.first()
                            val pos = engagementRepository.getEngagement(currentArtifact.id)?.lastPositionMs ?: 0L
                            
                            completer.set(
                                MediaSession.MediaItemsWithStartPosition(
                                    mediaItems,
                                    lastIndex,
                                    pos
                                )
                            )
                        } else {
                            completer.setException(Exception("No items to resume"))
                        }
                    } catch (e: Exception) {
                        completer.setException(e)
                    }
                }
                
                return completer
            }

            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val rootItem = MediaItem.Builder()
                    .setMediaId("ROOT")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setTitle("Artifact")
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                return if (parentId == "ROOT") {
                    val nowPlaying = MediaItem.Builder()
                        .setMediaId("NOW_PLAYING")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Recently Heard")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build()
                        )
                        .build()
                    Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(nowPlaying), params))
                } else {
                    Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
            }
            
            override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
                super.onPostConnect(session, controller)
                Log.d("PlaybackService", "Controller connected: ${controller.packageName}")
            }
        }

        mediaSession = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(createSessionActivityPendingIntent())
            .build()
    }

    private fun createMediaItem(artifact: com.saurabh.artifact.model.Artifact): MediaItem {
        return MediaItem.Builder()
            .setUri(artifact.audioUrl)
            .setMediaId(artifact.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(artifact.title)
                    .setArtist(artifact.author.name)
                    .setAlbumTitle("Reflections")
                    .setGenre(artifact.emotion)
                    .build()
            )
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || (!player.playWhenReady) || (player.mediaItemCount == 0) || (player.playbackState == Player.STATE_IDLE)) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.d("PlaybackService", "onDestroy - Releasing resources")
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
