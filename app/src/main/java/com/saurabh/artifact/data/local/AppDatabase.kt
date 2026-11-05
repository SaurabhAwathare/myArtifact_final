package com.saurabh.artifact.data.local

import androidx.room.AutoMigration
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
        UserConsumedPromptEntity::class,
    ],
    version = 78,
    autoMigrations = [
        AutoMigration(from = 64, to = 65),
        AutoMigration(from = 65, to = 66),
        AutoMigration(from = 66, to = 67),
        AutoMigration(from = 67, to = 68),
        AutoMigration(from = 70, to = 71),
        AutoMigration(from = 71, to = 72),
        AutoMigration(from = 72, to = 73)
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
    abstract fun userConsumedPromptDao(): UserConsumedPromptDao

    /**
     * Clears transient session, feed, and cache tables during logout,
     * while explicitly preserving account-scoped offline interaction and local data tables:
     * [PendingInteractionEntity] (`pending_interactions`), [DeadLetterInteractionEntity] (`dead_letter_interactions`),
     * [ArtifactEngagement] (`artifact_engagement`), [ArtifactDraftEntity] (`artifact_drafts`),
     * and [UserConsumedPromptEntity] (`user_consumed_prompts`).
     */
    open fun clearSessionTables() {
        runInTransaction {
            val db = openHelper.writableDatabase
            db.execSQL("DELETE FROM prompts")
            db.execSQL("DELETE FROM artifacts")
            db.execSQL("DELETE FROM upload_tasks")
            db.execSQL("DELETE FROM current_user_profile")
            db.execSQL("DELETE FROM reported_artifacts")
            db.execSQL("DELETE FROM ignored_users")
        }
    }
}
