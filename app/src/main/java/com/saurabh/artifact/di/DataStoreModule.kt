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
private val Context.maintenanceDataStore: DataStore<Preferences> by preferencesDataStore(name = "maintenance_settings")
private val Context.dbEncryptionDataStore: DataStore<Preferences> by preferencesDataStore(name = "db_encryption_prefs")

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

    @Provides
    @Singleton
    @Named("maintenanceDataStore")
    fun provideMaintenanceDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.maintenanceDataStore
    }

    @Provides
    @Singleton
    @Named("dbEncryptionDataStore")
    fun provideDbEncryptionDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dbEncryptionDataStore
    }
}
