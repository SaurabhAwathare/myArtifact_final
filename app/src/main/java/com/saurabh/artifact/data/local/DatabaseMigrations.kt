package com.saurabh.artifact.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Centrally managed database migrations for AppDatabase.
 * Each migration is documented with its business and technical purpose.
 *
 * Current baseline is established at Version 60.
 * Historical migrations (1-59) have been archived for the first production release.
 */
object DatabaseMigrations {

    /**
     * Migration 60 -> 61: User-Anchored Draft Ownership.
     * Adds 'userId' column to 'artifact_drafts' to isolate local data between accounts.
     * Includes a composite index on (userId, updatedAt) for optimized feed performance.
     */
    val MIGRATION_60_61 = object : Migration(60, 61) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. Add non-nullable userId column with legacy default
            db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN userId TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'")
            
            // 2. Create composite index for ownership isolation performance
            db.execSQL("CREATE INDEX IF NOT EXISTS index_artifact_drafts_userId_updatedAt ON artifact_drafts (userId, updatedAt)")
        }
    }

    /**
     * Migration 61 -> 62: Encryption Metadata for Cached Artifacts.
     * Adds 'isEncrypted' column to 'artifacts' table to prevent data loss 
     * when playing encrypted remote artifacts from local cache.
     */
    val MIGRATION_61_62 = object : Migration(61, 62) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE artifacts ADD COLUMN isEncrypted INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Migration 62 -> 63: Upload Session Invalidation.
     * Adds 'uploadFormatVersion' to 'artifact_drafts' to track the content format
     * associated with a resumable upload session (Encrypted vs Decrypted).
     */
    val MIGRATION_62_63 = object : Migration(62, 63) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN uploadFormatVersion INTEGER NOT NULL DEFAULT 1")
        }
    }

    /**
     * Migration 63 -> 64: Prompt Depth and Consumption Tracking.
     * Adds 'depthLevel' and 'isConsumed' to 'prompts' table.
     * Preserves existing history by marking prompts with usageCount > 0 as consumed.
     */
    val MIGRATION_63_64 = object : Migration(63, 64) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE prompts ADD COLUMN depthLevel INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE prompts ADD COLUMN isConsumed INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE prompts SET isConsumed = 1 WHERE usageCount > 0")
        }
    }
    
    /**
     * Migration 68 -> 69: Hard Account Isolation for Engagement and Moderation.
     * Scopes 'artifact_engagement' and 'ignored_users' by current authenticated user.
     * Legacy global records are discarded to prevent cross-account contamination.
     */
    val MIGRATION_68_69 = object : Migration(68, 69) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // R011: artifact_engagement - Recreate with (userId, artifactId) PK
            db.execSQL("DROP TABLE IF EXISTS `artifact_engagement` ")
            db.execSQL("""
                CREATE TABLE `artifact_engagement` (
                    `userId` TEXT NOT NULL, 
                    `artifactId` TEXT NOT NULL, 
                    `versionTag` TEXT NOT NULL, 
                    `durationMs` INTEGER NOT NULL, 
                    `audioChecksum` TEXT NOT NULL, 
                    `coverage` BLOB NOT NULL, 
                    `lastPositionMs` INTEGER NOT NULL, 
                    `furthestPositionMs` INTEGER NOT NULL, 
                    `hasReachedEnd` INTEGER NOT NULL, 
                    `lastUpdated` INTEGER NOT NULL, 
                    `syncState` TEXT NOT NULL, 
                    `lastSyncAttempt` INTEGER NOT NULL, 
                    `lastSyncSuccess` INTEGER NOT NULL, 
                    `syncRetryCount` INTEGER NOT NULL, 
                    `lastSyncError` TEXT, 
                    `isCommentUnlocked` INTEGER NOT NULL, 
                    `unlockTimestamp` INTEGER, 
                    `engagementState` TEXT NOT NULL, 
                    `unlockReason` TEXT, 
                    `remoteUpdatedAt` INTEGER, 
                    `reviewTrackingVersion` INTEGER NOT NULL, 
                    `segmentSizeMs` INTEGER NOT NULL, 
                    PRIMARY KEY(`userId`, `artifactId`)
                )
            """)

            // R021: ignored_users - Recreate with (ownerUserId, userId) PK
            db.execSQL("DROP TABLE IF EXISTS `ignored_users` ")
            db.execSQL("""
                CREATE TABLE `ignored_users` (
                    `ownerUserId` TEXT NOT NULL, 
                    `userId` TEXT NOT NULL, 
                    `createdAt` INTEGER NOT NULL, 
                    PRIMARY KEY(`ownerUserId`, `userId`)
                )
            """)
        }
    }
    
    val ALL_MIGRATIONS = arrayOf<Migration>(
        MIGRATION_60_61,
        MIGRATION_61_62,
        MIGRATION_62_63,
        MIGRATION_63_64,
        MIGRATION_68_69
    )
}
