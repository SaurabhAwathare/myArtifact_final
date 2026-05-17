package com.saurabh.artifact.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [QueuedUpload::class, ArtifactDraftEntity::class, PromptEntity::class],
    version = 19,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun queuedUploadDao(): QueuedUploadDao
    abstract fun draftDao(): DraftDao
    abstract fun promptDao(): PromptDao

    companion object {
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `prompts` (
                        `id` TEXT NOT NULL, 
                        `text` TEXT NOT NULL, 
                        `category` TEXT NOT NULL, 
                        `tone` TEXT NOT NULL, 
                        `isFavorite` INTEGER NOT NULL DEFAULT 0, 
                        `usageCount` INTEGER NOT NULL DEFAULT 0, 
                        `lastUsedTimestamp` INTEGER NOT NULL DEFAULT 0, 
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN emotionalRiskScore REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN publishConfidence REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN isEmotionalReady INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN reactionVisibility TEXT")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // artifact_drafts
                db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN syncState TEXT NOT NULL DEFAULT 'DRAFT'")
                db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN uploadedBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN totalBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN uploadSessionUri TEXT")
                db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN checksum TEXT")
                db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN deviceId TEXT")
                db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN transcriptionState TEXT NOT NULL DEFAULT 'IDLE'")

                // recording_drafts
                db.execSQL("ALTER TABLE recording_drafts ADD COLUMN checksum TEXT")
                db.execSQL("ALTER TABLE recording_drafts ADD COLUMN isEncrypted INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `artifact_drafts` (
                        `id` TEXT NOT NULL, 
                        `localAudioPath` TEXT NOT NULL, 
                        `localTranscriptPath` TEXT, 
                        `waveformPath` TEXT, 
                        `title` TEXT, 
                        `emotion` TEXT, 
                        `tags` TEXT NOT NULL, 
                        `durationMs` INTEGER NOT NULL, 
                        `createdAt` INTEGER NOT NULL, 
                        `updatedAt` INTEGER NOT NULL, 
                        `draftState` TEXT NOT NULL, 
                        `uploadStatus` TEXT NOT NULL, 
                        `uploadAttemptCount` INTEGER NOT NULL, 
                        `remoteArtifactId` TEXT, 
                        `isEncrypted` INTEGER NOT NULL, 
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queued_uploads ADD COLUMN createdAt INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add status column with default 'IDLE'
                db.execSQL("ALTER TABLE recording_drafts ADD COLUMN status TEXT NOT NULL DEFAULT 'IDLE'")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queued_uploads ADD COLUMN prompt TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
