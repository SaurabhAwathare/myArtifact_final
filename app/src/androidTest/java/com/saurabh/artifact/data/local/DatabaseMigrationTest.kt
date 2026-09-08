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

    /**
     * Simulates the confirmed failure mode where an installed database is at Version 69
     * but physically missing 'reported_artifacts' and 'ignored_users' tables.
     * Verifies MIGRATION_69_70 idempotently repairs the missing tables, creates required indices,
     * passes Room schema validation, and preserves existing Draft data completely intact.
     */
    @Test
    @Throws(IOException::class)
    fun migrate69To70_simulatedCorruptedV69_repairsMissingTablesAndPreservesDraft() {
        // 1. Create a valid Version 69 database with a populated Draft
        helper.createDatabase(TEST_DB, 69).apply {
            execSQL(
                """
                INSERT INTO artifact_drafts (
                    id, userId, localAudioPath, rawPcmPath, localTranscriptPath, waveformPath,
                    title, description, emotion, isPublic, isListened, tags, durationMs,
                    createdAt, updatedAt, status, lifecycle, uploadedBytes, totalBytes,
                    uploadSessionUri, uploadAttemptCount, isEncrypted, encryptionIv, checksum,
                    approvalToken, deviceFingerprint, publishApprovalTimestamp, reviewProgress,
                    deviceId, transcriptionState, remoteArtifactId, emotionalTone, primaryStyle,
                    safetyAnalysis, interruptionReason, lastCheckpointTimestamp, durableBytes,
                    isCorrupted, version, uploadFormatVersion, mimeType, amplitudeData,
                    reactionVisibility, uploadedAudioUrl, frozenTranscriptJson, frozenAudioPath,
                    transcriptSegmentsJson, sensitiveEntitiesJson, reviewCompleted, titleCompleted,
                    emotionCompleted, approvalCompleted, lastRecoveryAttemptAt, isDismissed,
                    localCleanupStatus, cleanupRetryCount
                ) VALUES (
                    'draft_repair_v69', 'user_repair_v69', '/sdcard/audio/draft_repair.wav', '/sdcard/pcm/draft_repair.pcm',
                    '/sdcard/transcript/draft_repair.txt', '/sdcard/waveform/draft_repair.json',
                    'Test Draft Title', 'Test Draft Description', 'JOY', 1, 0, '["test","repair"]', 12000,
                    1700000000000, 1700000001000, 'LOCAL_ONLY', 'RECORDED', 0, 50000,
                    'https://upload.session/1', 2, 1, 'iv_hex_string', 'md5_checksum_hash',
                    'approval_token_123', 'fp_abc_456', 1700000002000, 0.75,
                    'device_xyz', 'COMPLETED', 'remote_art_999', 'WARM', 'STORY',
                    'PASS', 'NONE', 1700000001000, 50000,
                    0, 1, 1, 'audio/wav', '[10,20,30,40,50]',
                    'PUBLIC', 'https://storage.url/audio.wav', '{"text":"hello"}', '/sdcard/audio/frozen.wav',
                    '[]', '[]', 1, 1,
                    1, 1, 1700000003000, 0,
                    'NOT_NEEDED', 0
                )
                """.trimIndent()
            )

            // 2. Simulate the database corruption by physically dropping reported_artifacts and ignored_users
            execSQL("DROP TABLE IF EXISTS reported_artifacts")
            execSQL("DROP TABLE IF EXISTS ignored_users")

            close()
        }

        // 3. Run MIGRATION_69_70 and perform strict Room schema validation against version 70
        val db = helper.runMigrationsAndValidate(TEST_DB, 70, true, DatabaseMigrations.MIGRATION_69_70)

        // 4. Verify reported_artifacts exists and functions with expected schema & index
        db.execSQL("INSERT INTO reported_artifacts (userId, artifactId, reportedAt) VALUES ('user_rep_1', 'art_rep_1', 1700000000000)")
        val repCursor = db.query("SELECT * FROM reported_artifacts WHERE userId = 'user_rep_1'")
        assert(repCursor.moveToFirst())
        assertEquals("art_rep_1", repCursor.getString(repCursor.getColumnIndexOrThrow("artifactId")))
        assertEquals(1700000000000L, repCursor.getLong(repCursor.getColumnIndexOrThrow("reportedAt")))
        repCursor.close()

        val repIndexCursor = db.query("PRAGMA index_list('reported_artifacts')")
        var indexFound = false
        while (repIndexCursor.moveToNext()) {
            val name = repIndexCursor.getString(repIndexCursor.getColumnIndexOrThrow("name"))
            if (name == "index_reported_artifacts_artifactId") {
                indexFound = true
                break
            }
        }
        repIndexCursor.close()
        assert(indexFound) { "Index index_reported_artifacts_artifactId was not created on reported_artifacts" }

        // 5. Verify ignored_users exists and functions with expected schema
        db.execSQL("INSERT INTO ignored_users (ownerUserId, userId, createdAt) VALUES ('owner_ign_1', 'user_ign_1', 1700000000000)")
        val ignCursor = db.query("SELECT * FROM ignored_users WHERE ownerUserId = 'owner_ign_1'")
        assert(ignCursor.moveToFirst())
        assertEquals("user_ign_1", ignCursor.getString(ignCursor.getColumnIndexOrThrow("userId")))
        assertEquals(1700000000000L, ignCursor.getLong(ignCursor.getColumnIndexOrThrow("createdAt")))
        ignCursor.close()

        // 6. Verify Draft row is completely intact with exact values preserved
        val draftCursor = db.query("SELECT * FROM artifact_drafts WHERE id = 'draft_repair_v69'")
        assert(draftCursor.moveToFirst())
        assertEquals("draft_repair_v69", draftCursor.getString(draftCursor.getColumnIndexOrThrow("id")))
        assertEquals("user_repair_v69", draftCursor.getString(draftCursor.getColumnIndexOrThrow("userId")))
        assertEquals("/sdcard/audio/draft_repair.wav", draftCursor.getString(draftCursor.getColumnIndexOrThrow("localAudioPath")))
        assertEquals("/sdcard/pcm/draft_repair.pcm", draftCursor.getString(draftCursor.getColumnIndexOrThrow("rawPcmPath")))
        assertEquals("Test Draft Title", draftCursor.getString(draftCursor.getColumnIndexOrThrow("title")))
        assertEquals("Test Draft Description", draftCursor.getString(draftCursor.getColumnIndexOrThrow("description")))
        assertEquals("JOY", draftCursor.getString(draftCursor.getColumnIndexOrThrow("emotion")))
        assertEquals(1, draftCursor.getInt(draftCursor.getColumnIndexOrThrow("isPublic")))
        assertEquals(0, draftCursor.getInt(draftCursor.getColumnIndexOrThrow("isListened")))
        assertEquals("[\"test\",\"repair\"]", draftCursor.getString(draftCursor.getColumnIndexOrThrow("tags")))
        assertEquals(12000L, draftCursor.getLong(draftCursor.getColumnIndexOrThrow("durationMs")))
        assertEquals(1700000000000L, draftCursor.getLong(draftCursor.getColumnIndexOrThrow("createdAt")))
        assertEquals(1700000001000L, draftCursor.getLong(draftCursor.getColumnIndexOrThrow("updatedAt")))
        assertEquals("LOCAL_ONLY", draftCursor.getString(draftCursor.getColumnIndexOrThrow("status")))
        assertEquals("RECORDED", draftCursor.getString(draftCursor.getColumnIndexOrThrow("lifecycle")))
        assertEquals(0L, draftCursor.getLong(draftCursor.getColumnIndexOrThrow("uploadedBytes")))
        assertEquals(50000L, draftCursor.getLong(draftCursor.getColumnIndexOrThrow("totalBytes")))
        assertEquals("https://upload.session/1", draftCursor.getString(draftCursor.getColumnIndexOrThrow("uploadSessionUri")))
        assertEquals(2, draftCursor.getInt(draftCursor.getColumnIndexOrThrow("uploadAttemptCount")))
        assertEquals(1, draftCursor.getInt(draftCursor.getColumnIndexOrThrow("isEncrypted")))
        assertEquals("iv_hex_string", draftCursor.getString(draftCursor.getColumnIndexOrThrow("encryptionIv")))
        assertEquals("md5_checksum_hash", draftCursor.getString(draftCursor.getColumnIndexOrThrow("checksum")))
        assertEquals("approval_token_123", draftCursor.getString(draftCursor.getColumnIndexOrThrow("approvalToken")))
        assertEquals("fp_abc_456", draftCursor.getString(draftCursor.getColumnIndexOrThrow("deviceFingerprint")))
        assertEquals(1700000002000L, draftCursor.getLong(draftCursor.getColumnIndexOrThrow("publishApprovalTimestamp")))
        assertEquals(0.75f, draftCursor.getFloat(draftCursor.getColumnIndexOrThrow("reviewProgress")), 0.001f)
        assertEquals("device_xyz", draftCursor.getString(draftCursor.getColumnIndexOrThrow("deviceId")))
        assertEquals("COMPLETED", draftCursor.getString(draftCursor.getColumnIndexOrThrow("transcriptionState")))
        assertEquals("remote_art_999", draftCursor.getString(draftCursor.getColumnIndexOrThrow("remoteArtifactId")))
        assertEquals("WARM", draftCursor.getString(draftCursor.getColumnIndexOrThrow("emotionalTone")))
        assertEquals("STORY", draftCursor.getString(draftCursor.getColumnIndexOrThrow("primaryStyle")))
        assertEquals("PASS", draftCursor.getString(draftCursor.getColumnIndexOrThrow("safetyAnalysis")))
        assertEquals("NONE", draftCursor.getString(draftCursor.getColumnIndexOrThrow("interruptionReason")))
        assertEquals(1700000001000L, draftCursor.getLong(draftCursor.getColumnIndexOrThrow("lastCheckpointTimestamp")))
        assertEquals(50000L, draftCursor.getLong(draftCursor.getColumnIndexOrThrow("durableBytes")))
        assertEquals(0, draftCursor.getInt(draftCursor.getColumnIndexOrThrow("isCorrupted")))
        assertEquals(1, draftCursor.getInt(draftCursor.getColumnIndexOrThrow("version")))
        assertEquals(1, draftCursor.getInt(draftCursor.getColumnIndexOrThrow("uploadFormatVersion")))
        assertEquals("audio/wav", draftCursor.getString(draftCursor.getColumnIndexOrThrow("mimeType")))
        assertEquals("[10,20,30,40,50]", draftCursor.getString(draftCursor.getColumnIndexOrThrow("amplitudeData")))
        assertEquals("PUBLIC", draftCursor.getString(draftCursor.getColumnIndexOrThrow("reactionVisibility")))
        assertEquals("https://storage.url/audio.wav", draftCursor.getString(draftCursor.getColumnIndexOrThrow("uploadedAudioUrl")))
        assertEquals("{\"text\":\"hello\"}", draftCursor.getString(draftCursor.getColumnIndexOrThrow("frozenTranscriptJson")))
        assertEquals("/sdcard/audio/frozen.wav", draftCursor.getString(draftCursor.getColumnIndexOrThrow("frozenAudioPath")))
        assertEquals("[]", draftCursor.getString(draftCursor.getColumnIndexOrThrow("transcriptSegmentsJson")))
        assertEquals("[]", draftCursor.getString(draftCursor.getColumnIndexOrThrow("sensitiveEntitiesJson")))
        assertEquals(1, draftCursor.getInt(draftCursor.getColumnIndexOrThrow("reviewCompleted")))
        assertEquals(1, draftCursor.getInt(draftCursor.getColumnIndexOrThrow("titleCompleted")))
        assertEquals(1, draftCursor.getInt(draftCursor.getColumnIndexOrThrow("emotionCompleted")))
        assertEquals(1, draftCursor.getInt(draftCursor.getColumnIndexOrThrow("approvalCompleted")))
        assertEquals(1700000003000L, draftCursor.getLong(draftCursor.getColumnIndexOrThrow("lastRecoveryAttemptAt")))
        assertEquals(0, draftCursor.getInt(draftCursor.getColumnIndexOrThrow("isDismissed")))
        assertEquals("NOT_NEEDED", draftCursor.getString(draftCursor.getColumnIndexOrThrow("localCleanupStatus")))
        assertEquals(0, draftCursor.getInt(draftCursor.getColumnIndexOrThrow("cleanupRetryCount")))
        draftCursor.close()
    }

    /**
     * Verifies that MIGRATION_69_70 is idempotent when applied to a healthy Version 69 database.
     * Pre-existing reported_artifacts and ignored_users data remain completely intact.
     */
    @Test
    @Throws(IOException::class)
    fun migrate69To70_normalV69_idempotentAndPreservesExistingData() {
        helper.createDatabase(TEST_DB, 69).apply {
            execSQL("INSERT INTO reported_artifacts (userId, artifactId, reportedAt) VALUES ('user_norm_1', 'art_norm_1', 1700000005000)")
            execSQL("INSERT INTO ignored_users (ownerUserId, userId, createdAt) VALUES ('owner_norm_1', 'user_norm_2', 1700000006000)")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 70, true, DatabaseMigrations.MIGRATION_69_70)

        val repCursor = db.query("SELECT * FROM reported_artifacts WHERE userId = 'user_norm_1'")
        assert(repCursor.moveToFirst())
        assertEquals("art_norm_1", repCursor.getString(repCursor.getColumnIndexOrThrow("artifactId")))
        assertEquals(1700000005000L, repCursor.getLong(repCursor.getColumnIndexOrThrow("reportedAt")))
        repCursor.close()

        val ignCursor = db.query("SELECT * FROM ignored_users WHERE ownerUserId = 'owner_norm_1'")
        assert(ignCursor.moveToFirst())
        assertEquals("user_norm_2", ignCursor.getString(ignCursor.getColumnIndexOrThrow("userId")))
        assertEquals(1700000006000L, ignCursor.getLong(ignCursor.getColumnIndexOrThrow("createdAt")))
        ignCursor.close()
    }

    /**
     * Verifies the full migration chain from baseline Version 60 to Version 70.
     */
    @Test
    @Throws(IOException::class)
    fun migrateAll_60_to_70() {
        helper.createDatabase(TEST_DB, 60).apply {
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
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 70, true, *DatabaseMigrations.ALL_MIGRATIONS)

        val draftCursor = db.query("SELECT * FROM artifact_drafts WHERE id = 'draft_60'")
        assert(draftCursor.moveToFirst())
        assertEquals("user_60", draftCursor.getString(draftCursor.getColumnIndexOrThrow("userId")))
        draftCursor.close()
    }
}
