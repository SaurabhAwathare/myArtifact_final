package com.saurabh.artifact.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName ?: "com.saurabh.artifact.data.local.AppDatabase",
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate53To54() {
        // Create earliest version of the database.
        helper.createDatabase(TEST_DB, 53).apply {
            // Insert some data for version 53
            execSQL("INSERT INTO pending_interactions (artifactId, interactionType, action, createdAt, correlationId, retryCount) VALUES ('art1', 'SAVE', 'ADD', 123456789, 'corr1', 0)")
            execSQL("INSERT INTO dead_letter_interactions (originalId, artifactId, interactionType, action, createdAt, correlationId, failedAt, failureType, retryCount) VALUES (1, 'art2', 'REACTION', 'ADD', 123456780, 'corr2', 123456781, 'PERMANENT', 0)")
            close()
        }

        // Open latest version of the database. Room will validate the schema
        // once all migrations execute.
        val db = helper.runMigrationsAndValidate(TEST_DB, 54, true, DatabaseMigrations.MIGRATION_53_54)

        // Verify data survived and new column exists with default value
        val pendingCursor = db.query("SELECT * FROM pending_interactions")
        assert(pendingCursor.moveToFirst())
        assert(pendingCursor.getString(pendingCursor.getColumnIndexOrThrow("userId")) == "")
        assert(pendingCursor.getString(pendingCursor.getColumnIndexOrThrow("artifactId")) == "art1")
        pendingCursor.close()

        val dlqCursor = db.query("SELECT * FROM dead_letter_interactions")
        assert(dlqCursor.moveToFirst())
        assert(dlqCursor.getString(dlqCursor.getColumnIndexOrThrow("userId")) == "")
        assert(dlqCursor.getString(dlqCursor.getColumnIndexOrThrow("artifactId")) == "art2")
        dlqCursor.close()
    }
}
