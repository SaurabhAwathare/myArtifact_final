package com.saurabh.artifact.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        PromptEntity::class, 
        ArtifactEngagement::class, 
        ArtifactEntity::class,
        ArtifactDraftEntity::class,
        UploadTaskEntity::class,
        PendingInteractionEntity::class,
        UserLocalEntity::class,
        DeadLetterInteractionEntity::class,
        ReportedArtifactEntity::class,
    ],
    version = 62,
    autoMigrations = [
        // Auto-migrations can be added here for simple schema changes
    ],
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun promptDao(): PromptDao
    abstract fun engagementDao(): EngagementDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun draftDao(): DraftDao
    abstract fun uploadTaskDao(): UploadTaskDao
    abstract fun pendingInteractionDao(): PendingInteractionDao
    abstract fun deadLetterInteractionDao(): DeadLetterInteractionDao
    abstract fun userDao(): UserDao
    abstract fun reportedArtifactDao(): ReportedArtifactDao

    companion object {
        // Migrations are centrally managed in DatabaseMigrations
        fun getMigrations() = DatabaseMigrations.ALL_MIGRATIONS
    }
}
