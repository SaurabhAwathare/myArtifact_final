package com.saurabh.artifact.di

import android.content.Context
import androidx.room.Room
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.DatabaseMigrations
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.local.PromptDao
import com.saurabh.artifact.security.DatabaseEncryptionManager
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
    fun provideAppDatabase(
        @ApplicationContext context: Context,
        encryptionManager: DatabaseEncryptionManager
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "artifact_db",
        ).openHelperFactory(encryptionManager.getEncryptionFactory())
            .addMigrations(*DatabaseMigrations.ALL_MIGRATIONS)
            .build()
    }

    @Provides
    fun provideDraftDao(database: AppDatabase): DraftDao {
        return database.draftDao()
    }

    @Provides
    fun providePromptDao(database: AppDatabase): PromptDao {
        return database.promptDao()
    }

    @Provides
    fun provideEngagementDao(database: AppDatabase): com.saurabh.artifact.data.local.EngagementDao {
        return database.engagementDao()
    }

    @Provides
    fun provideArtifactDao(database: AppDatabase): com.saurabh.artifact.data.local.ArtifactDao {
        return database.artifactDao()
    }

    @Provides
    fun provideUploadTaskDao(database: AppDatabase): com.saurabh.artifact.data.local.UploadTaskDao {
        return database.uploadTaskDao()
    }

    @Provides
    fun providePendingInteractionDao(database: AppDatabase): com.saurabh.artifact.data.local.PendingInteractionDao {
        return database.pendingInteractionDao()
    }

    @Provides
    fun provideDeadLetterInteractionDao(database: AppDatabase): com.saurabh.artifact.data.local.DeadLetterInteractionDao {
        return database.deadLetterInteractionDao()
    }

    @Provides
    fun provideUserDao(database: AppDatabase): com.saurabh.artifact.data.local.UserDao {
        return database.userDao()
    }

    @Provides
    fun provideReportedArtifactDao(database: AppDatabase): com.saurabh.artifact.data.local.ReportedArtifactDao {
        return database.reportedArtifactDao()
    }

    @Provides
    fun provideIgnoredUserDao(database: AppDatabase): com.saurabh.artifact.data.local.IgnoredUserDao {
        return database.ignoredUserDao()
    }
}
