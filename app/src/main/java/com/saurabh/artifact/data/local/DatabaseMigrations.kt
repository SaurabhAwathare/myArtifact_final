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
    
    val ALL_MIGRATIONS = arrayOf<Migration>(
        MIGRATION_60_61
    )
}
