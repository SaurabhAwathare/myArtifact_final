package com.saurabh.artifact.di

import android.content.Context
import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.audio.AudioPlayer
import com.saurabh.artifact.audio.AudioRecorder
import com.saurabh.artifact.audio.PlaybackSessionManager
import com.saurabh.artifact.nlp.EmotionAnalyzer
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    @Singleton
    fun providePlaybackSessionManager(
        @ApplicationContext context: Context,
        playbackPositionDao: com.saurabh.artifact.data.local.PlaybackPositionDao,
        cleanupManager: Lazy<ArtifactCleanupManager>
    ): PlaybackSessionManager = 
        PlaybackSessionManager(context, playbackPositionDao, cleanupManager)

    @Provides
    @Singleton
    fun provideAudioPlayer(playbackSessionManager: PlaybackSessionManager): AudioPlayer = 
        AudioPlayer(playbackSessionManager)

    @Provides
    @Singleton
    fun provideAudioRecorder(@ApplicationContext context: Context): AudioRecorder = 
        AudioRecorder(context)

    @Provides
    @Singleton
    fun provideWavRecoveryManager(): com.saurabh.artifact.audio.WavRecoveryManager = 
        com.saurabh.artifact.audio.WavRecoveryManager()

    @Provides
    @Singleton
    fun provideEmotionAnalyzer(): EmotionAnalyzer = EmotionAnalyzer()

    @Provides
    @Singleton
    fun provideReviewValidator(): com.saurabh.artifact.audio.validation.ReviewValidator = 
        com.saurabh.artifact.audio.validation.DefaultReviewValidator()
}
