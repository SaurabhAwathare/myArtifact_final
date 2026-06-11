package com.saurabh.artifact.di

import com.saurabh.artifact.service.ReflectionAIService
import com.saurabh.artifact.service.ReflectionAIServiceImpl
import com.saurabh.artifact.service.EntityExtractorWrapper
import com.saurabh.artifact.service.MlKitEntityExtractorWrapper
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceModule {

    @Binds
    @Singleton
    abstract fun bindReflectionAIService(
        reflectionAIServiceImpl: ReflectionAIServiceImpl,
    ): ReflectionAIService

    @Binds
    @Singleton
    abstract fun bindEntityExtractorWrapper(
        mlKitEntityExtractorWrapper: MlKitEntityExtractorWrapper,
    ): EntityExtractorWrapper
}
