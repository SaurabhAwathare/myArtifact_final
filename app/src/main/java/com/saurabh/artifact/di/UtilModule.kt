package com.saurabh.artifact.di

import com.saurabh.artifact.util.EnvironmentProvider
import com.saurabh.artifact.util.EnvironmentProviderImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UtilModule {

    @Binds
    @Singleton
    abstract fun bindEnvironmentProvider(
        impl: EnvironmentProviderImpl
    ): EnvironmentProvider
}
