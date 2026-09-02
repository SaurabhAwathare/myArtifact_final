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
    version = 69,
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

    /**
     * Clears all transient session, feed, and cache tables during logout,
     * while explicitly preserving local draft metadata in [ArtifactDraftEntity] (`artifact_drafts`).
     */
    open fun clearSessionTables() {
        runInTransaction {
            val db = openHelper.writableDatabase
            db.execSQL("DELETE FROM prompts")
            db.execSQL("DELETE FROM artifact_engagement")
            db.execSQL("DELETE FROM artifacts")
            db.execSQL("DELETE FROM upload_tasks")
            db.execSQL("DELETE FROM pending_interactions")
            db.execSQL("DELETE FROM current_user_profile")
            db.execSQL("DELETE FROM dead_letter_interactions")
            db.execSQL("DELETE FROM reported_artifacts")
            db.execSQL("DELETE FROM ignored_users")
        }
    }
}
