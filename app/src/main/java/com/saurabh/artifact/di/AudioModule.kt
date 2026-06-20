package com.saurabh.artifact.di

import android.content.Context
import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.audio.AudioRecorder
import com.saurabh.artifact.audio.PlaybackAnalyticsManager
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackSessionManager
import com.saurabh.artifact.audio.PlaybackSettingsDataStore
import com.saurabh.artifact.audio.ReviewAuthorityService
import com.saurabh.artifact.audio.ReviewSessionManager
import com.saurabh.artifact.audio.TransientPlayerManager
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
        reviewSessionManager: ReviewSessionManager,
        reviewAuthorityService: ReviewAuthorityService,
        transientPlayerManager: TransientPlayerManager,
        analytics: PlaybackAnalyticsManager
    ): PlaybackCoordinator = 
        PlaybackCoordinator(
            playbackSessionManager,
            reviewSessionManager,
            reviewAuthorityService,
            transientPlayerManager,
            analytics
        )

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
    fun providePublishingReviewPolicy(): com.saurabh.artifact.domain.review.publishing.PublishingReviewPolicy = 
        com.saurabh.artifact.domain.review.publishing.PublishingReviewPolicy()

    @Provides
    @Singleton
    fun providePublishingReviewValidator(): com.saurabh.artifact.domain.review.publishing.PublishingReviewValidator = 
        com.saurabh.artifact.domain.review.publishing.PublishingReviewValidator()

    @Provides
    @Singleton
    fun provideCommentUnlockPolicy(): com.saurabh.artifact.domain.review.comments.CommentUnlockPolicy = 
        com.saurabh.artifact.domain.review.comments.CommentUnlockPolicy()

    @Provides
    @Singleton
    fun provideCommentUnlockValidator(): com.saurabh.artifact.domain.review.comments.CommentUnlockValidator = 
        com.saurabh.artifact.domain.review.comments.CommentUnlockValidator()
}
