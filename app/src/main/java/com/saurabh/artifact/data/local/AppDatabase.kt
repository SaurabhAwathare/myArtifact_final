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
        IgnoredUserEntity::class,
    ],
    version = 68,
    autoMigrations = [
        androidx.room.AutoMigration(from = 64, to = 65),
        androidx.room.AutoMigration(from = 65, to = 66),
        androidx.room.AutoMigration(from = 66, to = 67),
        androidx.room.AutoMigration(from = 67, to = 68)
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
    abstract fun ignoredUserDao(): IgnoredUserDao
}
