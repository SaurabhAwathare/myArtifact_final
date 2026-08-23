package com.saurabh.artifact.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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

    /**
     * Verifies the full migration chain from the production baseline (v60) to the current version.
     */
    @Test
    @Throws(IOException::class)
    fun migrateAll_60_to_64() {
        // 1. Create database at Version 60 (Production Baseline)
        helper.createDatabase(TEST_DB, 60).apply {
            // Populate representative data
            execSQL(
                """
                INSERT INTO artifact_drafts (
                    id, userId, localAudioPath, isPublic, isListened, tags, 
                    durationMs, createdAt, updatedAt, status, lifecycle, 
                    uploadedBytes, totalBytes, uploadAttemptCount, isEncrypted, 
                    reviewProgress, transcriptionState, lastCheckpointTimestamp, 
                    durableBytes, isCorrupted, version, mimeType, amplitudeData,
                    reviewCompleted, titleCompleted, emotionCompleted, approvalCompleted,
                    lastRecoveryAttemptAt, isDismissed, cleanupRetryCount
                ) VALUES (
                    'draft_60', 'user_60', '/path/60', 1, 0, '[]', 
                    0, 123456789, 123456789, 'LocalOnly', 'RECORDING', 
                    0, 0, 0, 0, 
                    0.0, 'IDLE', 123456789, 
                    0, 0, 1, 'audio/wav', '[]',
                    0, 0, 0, 0,
                    0, 0, 0
                )
                """.trimIndent()
            )

            execSQL(
                """
                INSERT INTO artifacts (
                    id, userId, authorAnonymousId, authorName, authorSigil, 
                    authorSigilSeed, authorSigilColor, authorSigilConfigJson, 
                    audioUrl, createdAt, durationMs, title, description, 
                    emotion, emotionTag, playCount, reactionCount, 
                    commentCount, reportCount, safetyConcernCount, reporterIds,
                    amplitudeData, status, isDraft, lastUpdated
                ) VALUES (
                    'art_60', 'user_60', 'anon_60', 'Name', 'sigil', 
                    'seed', 'color', '{}', 
                    'url', 123456789, 1000, 'Title', 'Desc', 
                    'NEUTRAL', 'tag', 0, 0, 
                    0, 0, 0, '[]',
                    '[]', 'ACTIVE', 0, 123456789
                )
                """.trimIndent()
            )
            
            execSQL(
                """
                INSERT INTO prompts (
                    id, category, text, context, usageCount, createdAt, lastUsedAt
                ) VALUES (
                    'prompt_60', 'CALM', 'Text', 'Context', 1, 123456789, 123456789
                )
                """.trimIndent()
            )
            close()
        }

        // 2. Run all migrations up to v64
        val db = helper.runMigrationsAndValidate(TEST_DB, 64, true, *DatabaseMigrations.ALL_MIGRATIONS)

        // 3. Verify Data Integrity & New Fields
        
        // Check artifact_drafts (added userId in 61, uploadFormatVersion in 63)
        val draftCursor = db.query("SELECT * FROM artifact_drafts WHERE id = 'draft_60'")
        assert(draftCursor.moveToFirst())
        assertEquals("user_60", draftCursor.getString(draftCursor.getColumnIndexOrThrow("userId")))
        assertEquals(1, draftCursor.getInt(draftCursor.getColumnIndexOrThrow("uploadFormatVersion")))
        draftCursor.close()

        // Check artifacts (added isEncrypted in 62)
        val artCursor = db.query("SELECT * FROM artifacts WHERE id = 'art_60'")
        assert(artCursor.moveToFirst())
        assertEquals(0, artCursor.getInt(artCursor.getColumnIndexOrThrow("isEncrypted")))
        artCursor.close()
        
        // Check prompts (added depthLevel and isConsumed in 64)
        val promptCursor = db.query("SELECT * FROM prompts WHERE id = 'prompt_60'")
        assert(promptCursor.moveToFirst())
        assertEquals(1, promptCursor.getInt(promptCursor.getColumnIndexOrThrow("depthLevel")))
        assertEquals(1, promptCursor.getInt(promptCursor.getColumnIndexOrThrow("isConsumed")))
        promptCursor.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate63To64_isolated() {
        helper.createDatabase(TEST_DB, 63).apply {
            execSQL("INSERT INTO prompts (id, category, text, context, usageCount, createdAt, lastUsedAt) VALUES ('p1', 'CALM', 't', 'c', 1, 1, 1)")
            execSQL("INSERT INTO prompts (id, category, text, context, usageCount, createdAt, lastUsedAt) VALUES ('p2', 'CALM', 't', 'c', 0, 1, 0)")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 64, true, DatabaseMigrations.MIGRATION_63_64)
        
        val cursor1 = db.query("SELECT depthLevel, isConsumed FROM prompts WHERE id = 'p1'")
        assert(cursor1.moveToFirst())
        assertEquals(1, cursor1.getInt(0))
        assertEquals(1, cursor1.getInt(1))
        cursor1.close()

        val cursor2 = db.query("SELECT isConsumed FROM prompts WHERE id = 'p2'")
        assert(cursor2.moveToFirst())
        assertEquals(0, cursor2.getInt(0))
        cursor2.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate60To61_isolated() {
        helper.createDatabase(TEST_DB, 60).apply {
            execSQL("INSERT INTO artifact_drafts (id, localAudioPath, isPublic, isListened, tags, durationMs, createdAt, updatedAt, status, lifecycle, uploadedBytes, totalBytes, uploadAttemptCount, isEncrypted, reviewProgress, transcriptionState, lastCheckpointTimestamp, durableBytes, isCorrupted, version, mimeType, amplitudeData, reviewCompleted, titleCompleted, emotionCompleted, approvalCompleted, lastRecoveryAttemptAt, isDismissed, cleanupRetryCount) VALUES ('d1', 'p1', 1, 0, '[]', 0, 1, 1, 'LocalOnly', 'RECORDING', 0, 0, 0, 0, 0.0, 'IDLE', 1, 0, 0, 1, 'wav', '[]', 0, 0, 0, 0, 0, 0, 0)")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 61, true, DatabaseMigrations.MIGRATION_60_61)
        val cursor = db.query("SELECT userId FROM artifact_drafts WHERE id = 'd1'")
        assert(cursor.moveToFirst())
        assertEquals("LEGACY_UNKNOWN", cursor.getString(0))
        cursor.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate61To62_isolated() {
        helper.createDatabase(TEST_DB, 61).apply {
            execSQL("INSERT INTO artifacts (id, userId, authorAnonymousId, authorName, authorSigil, authorSigilSeed, authorSigilColor, authorSigilConfigJson, audioUrl, createdAt, durationMs, title, description, emotion, emotionTag, playCount, reactionCount, commentCount, reportCount, safetyConcernCount, reporterIds, amplitudeData, status, isDraft, lastUpdated) VALUES ('a1', 'u1', 'an1', 'n', 's', 'ss', 'c', '{}', 'url', 1, 1, 't', 'd', 'NEUTRAL', 'tag', 0, 0, 0, 0, 0, '[]', '[]', 'ACTIVE', 0, 1)")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 62, true, DatabaseMigrations.MIGRATION_61_62)
        val cursor = db.query("SELECT isEncrypted FROM artifacts WHERE id = 'a1'")
        assert(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(0))
        cursor.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate62To63_isolated() {
        helper.createDatabase(TEST_DB, 62).apply {
            execSQL("INSERT INTO artifact_drafts (id, userId, localAudioPath, isPublic, isListened, tags, durationMs, createdAt, updatedAt, status, lifecycle, uploadedBytes, totalBytes, uploadAttemptCount, isEncrypted, reviewProgress, transcriptionState, lastCheckpointTimestamp, durableBytes, isCorrupted, version, mimeType, amplitudeData, reviewCompleted, titleCompleted, emotionCompleted, approvalCompleted, lastRecoveryAttemptAt, isDismissed, cleanupRetryCount) VALUES ('d2', 'u2', 'p2', 1, 0, '[]', 0, 1, 1, 'LocalOnly', 'RECORDING', 0, 0, 0, 0, 0.0, 'IDLE', 1, 0, 0, 1, 'wav', '[]', 0, 0, 0, 0, 0, 0, 0)")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 63, true, DatabaseMigrations.MIGRATION_62_63)
        val cursor = db.query("SELECT uploadFormatVersion FROM artifact_drafts WHERE id = 'd2'")
        assert(cursor.moveToFirst())
        assertEquals(1, cursor.getInt(0))
        cursor.close()
    }
}
