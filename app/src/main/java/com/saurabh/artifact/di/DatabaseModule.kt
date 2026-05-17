package com.saurabh.artifact.di

import android.content.Context
import androidx.room.Room
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.PromptDao
import com.saurabh.artifact.data.local.QueuedUploadDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "artifact_db",
        ).addMigrations(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_11_12,
            AppDatabase.MIGRATION_12_13,
            AppDatabase.MIGRATION_14_15
        ).fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideQueuedUploadDao(database: AppDatabase): QueuedUploadDao {
        return database.queuedUploadDao()
    }

    @Provides
    fun provideDraftDao(database: AppDatabase): DraftDao {
        return database.draftDao()
    }

    @Provides
    fun providePromptDao(database: AppDatabase): PromptDao {
        return database.promptDao()
    }
}
