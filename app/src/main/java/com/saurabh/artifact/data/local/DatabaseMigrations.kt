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

    // Placeholder for future migrations starting from version 60 -> 61
    
    val ALL_MIGRATIONS = arrayOf<Migration>(
        // Add new migrations here
    )
}
