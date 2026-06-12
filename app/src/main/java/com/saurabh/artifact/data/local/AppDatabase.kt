package com.saurabh.artifact.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        QueuedUpload::class, 
        PromptEntity::class, 
        ArtifactEngagement::class, 
        ArtifactEntity::class,
        ArtifactDraftEntity::class,
        UploadTaskEntity::class,
        PendingInteractionEntity::class,
        UserLocalEntity::class,
    ],
    version = 46,
    autoMigrations = [
        // Auto-migrations can be added here for simple schema changes
    ],
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun queuedUploadDao(): QueuedUploadDao
    abstract fun promptDao(): PromptDao
    abstract fun engagementDao(): EngagementDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun draftDao(): DraftDao
    abstract fun uploadTaskDao(): UploadTaskDao
    abstract fun pendingInteractionDao(): PendingInteractionDao
    abstract fun userDao(): UserDao

    companion object {
        // Migrations have been moved to DatabaseMigrations.kt
    }
}
