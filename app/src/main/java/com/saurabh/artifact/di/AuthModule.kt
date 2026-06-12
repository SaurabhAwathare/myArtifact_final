package com.saurabh.artifact.di

import android.content.Context
import androidx.credentials.CredentialManager
import com.saurabh.artifact.auth.CredentialHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    @Provides
    @Singleton
    fun provideCredentialManager(@ApplicationContext context: Context): CredentialManager {
        return CredentialManager.create(context)
    }

    @Provides
    @Singleton
    fun provideCredentialHelper(credentialManager: CredentialManager): CredentialHelper {
        return CredentialHelper(credentialManager)
    }
}
