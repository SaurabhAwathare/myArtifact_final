package com.saurabh.artifact.di

import android.content.Context
import com.saurabh.artifact.audio.AudioPlayer
import com.saurabh.artifact.audio.AudioRecorder
import com.saurabh.artifact.audio.analysis.*
import com.saurabh.artifact.nlp.EmotionAnalyzer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class AudioModule {

    @Binds
    @Singleton
    @Suppress("unused")
    abstract fun bindSignalProcessor(impl: ProductionSignalProcessor): SignalProcessor

    @Binds
    @Singleton
    @Suppress("unused")
    abstract fun bindFlowManager(impl: ProductionFlowManager): FlowManager

    @Binds
    @Singleton
    @Suppress("unused")
    abstract fun bindInsightManager(impl: ProductionInsightManager): InsightManager

    @Binds
    @Singleton
    @Suppress("unused")
    abstract fun bindAudioSemanticEditor(impl: com.saurabh.artifact.audio.FFmpegAudioSemanticEditor): com.saurabh.artifact.audio.AudioSemanticEditor

    companion object {
        @Provides
        @Singleton
        fun provideAudioPlayer(@ApplicationContext context: Context): AudioPlayer = AudioPlayer(context)

        @Provides
        @Singleton
        fun provideAudioRecorder(@ApplicationContext context: Context): AudioRecorder = AudioRecorder(context)

        @Provides
        @Singleton
        fun provideEmotionAnalyzer(): EmotionAnalyzer = EmotionAnalyzer()
    }
}
