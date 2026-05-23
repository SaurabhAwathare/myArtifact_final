package com.saurabh.artifact.di

import android.content.Context
import com.saurabh.artifact.audio.AudioPlayer
import com.saurabh.artifact.audio.AudioRecorder
import com.saurabh.artifact.audio.PlaybackSessionManager
import com.saurabh.artifact.nlp.EmotionAnalyzer
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
    fun providePlaybackSessionManager(@ApplicationContext context: Context): PlaybackSessionManager = 
        PlaybackSessionManager(context)

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
    fun provideEmotionAnalyzer(): EmotionAnalyzer = EmotionAnalyzer()
}
