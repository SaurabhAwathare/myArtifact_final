package com.saurabh.artifact.di

import android.content.Context
import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.audio.AudioRecorder
import com.saurabh.artifact.audio.PlaybackAnalyticsManager
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackSessionManager
import com.saurabh.artifact.audio.PlaybackSettingsDataStore
import com.saurabh.artifact.audio.ReviewSessionManager
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
        engagementRepository: com.saurabh.artifact.repository.EngagementRepository,
        cleanupManager: Lazy<ArtifactCleanupManager>,
        settingsDataStore: PlaybackSettingsDataStore,
        analytics: PlaybackAnalyticsManager,
        artifactRepository: Lazy<com.saurabh.artifact.repository.ArtifactRepository>
    ): PlaybackSessionManager = 
        PlaybackSessionManager(context, engagementRepository, cleanupManager, settingsDataStore, analytics, artifactRepository)

    @Provides
    @Singleton
    fun providePlaybackCoordinator(
        playbackSessionManager: PlaybackSessionManager,
        reviewSessionManager: ReviewSessionManager
    ): PlaybackCoordinator = 
        PlaybackCoordinator(playbackSessionManager, reviewSessionManager)

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
