package com.saurabh.artifact.di

import com.saurabh.artifact.backup.CloudProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BackupModule {
    @Provides
    @Singleton
    fun provideCloudProvider(): CloudProvider = object : CloudProvider {
        override suspend fun upload(fileName: String, data: ByteArray): Result<String> {
            // Dummy implementation for performance verification
            return Result.success("https://dummy.url/$fileName")
        }
    }
}
