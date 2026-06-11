package com.saurabh.artifact.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_session")
private val Context.debugDataStore: DataStore<Preferences> by preferencesDataStore(name = "debug_settings")

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    @Named("sessionDataStore")
    fun provideSessionDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.sessionDataStore
    }

    @Provides
    @Singleton
    @Named("debugDataStore")
    fun provideDebugDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.debugDataStore
    }
}
