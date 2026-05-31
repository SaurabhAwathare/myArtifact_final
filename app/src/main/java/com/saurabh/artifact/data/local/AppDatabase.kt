package com.saurabh.artifact.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [QueuedUpload::class, ArtifactDraftEntity::class, PromptEntity::class, PlaybackPosition::class, ArtifactReviewEvidence::class, ArtifactEntity::class],
    version = 34,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun queuedUploadDao(): QueuedUploadDao
    abstract fun draftDao(): DraftDao
    abstract fun promptDao(): PromptDao
    abstract fun playbackPositionDao(): PlaybackPositionDao
    abstract fun reviewDao(): ReviewDao
    abstract fun artifactDao(): ArtifactDao

    companion object {
        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `artifacts` (
                        `id` TEXT NOT NULL, 
                        `userId` TEXT NOT NULL, 
                        `authorAnonymousId` TEXT NOT NULL,
                        `authorName` TEXT NOT NULL, 
                        `authorSigil` TEXT NOT NULL, 
                        `authorAvatarSeed` TEXT NOT NULL, 
                        `authorAvatarColor` TEXT NOT NULL, 
                        `authorAvatarConfigJson` TEXT NOT NULL,
                        `audioUrl` TEXT NOT NULL, 
                        `createdAt` INTEGER NOT NULL, 
                        `durationMs` INTEGER NOT NULL, 
                        `title` TEXT NOT NULL, 
                        `description` TEXT NOT NULL, 
                        `emotion` TEXT NOT NULL, 
                        `emotionTag` TEXT NOT NULL, 
                        `playCount` INTEGER NOT NULL, 
                        `reactionCount` INTEGER NOT NULL, 
                        `commentCount` INTEGER NOT NULL, 
                        `amplitudeData` TEXT NOT NULL, 
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Fix artifact_review_evidence: audioChecksum was missing from previous migration
                val cursorEvidence = db.query("PRAGMA table_info(`artifact_review_evidence`)")
                var hasAudioChecksum = false
                while (cursorEvidence.moveToNext()) {
                    if (cursorEvidence.getString(cursorEvidence.getColumnIndexOrThrow("name")) == "audioChecksum") {
                        hasAudioChecksum = true
                    }
                }
                cursorEvidence.close()
                if (!hasAudioChecksum) {
                    db.execSQL("ALTER TABLE `artifact_review_evidence` ADD COLUMN `audioChecksum` TEXT NOT NULL DEFAULT ''")
                }
                
                // Fix artifact_drafts: missing columns from recent entity updates
                val cursorDrafts = db.query("PRAGMA table_info(`artifact_drafts`)")
                val existingColumns = mutableSetOf<String>()
                while (cursorDrafts.moveToNext()) {
                    existingColumns.add(cursorDrafts.getString(cursorDrafts.getColumnIndexOrThrow("name")))
                }
                cursorDrafts.close()

                if ("coveragePart1" !in existingColumns) {
                    db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `coveragePart1` INTEGER NOT NULL DEFAULT 0")
                }
                if ("coveragePart2" !in existingColumns) {
                    db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `coveragePart2` INTEGER NOT NULL DEFAULT 0")
                }
                if ("isPlaybackEnded" !in existingColumns) {
                    db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `isPlaybackEnded` INTEGER NOT NULL DEFAULT 0")
                }
            }
        }
        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Recreate the table with new structure
                db.execSQL("DROP TABLE IF EXISTS `artifact_review_evidence`")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `artifact_review_evidence` (
                        `artifactId` TEXT NOT NULL, 
                        `versionTag` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL, 
                        `audioChecksum` TEXT NOT NULL DEFAULT '',
                        `coverage` BLOB NOT NULL, 
                        `effortMap` TEXT NOT NULL, 
                        `furthestPositionMs` INTEGER NOT NULL,
                        `hasReachedEnd` INTEGER NOT NULL, 
                        `lastUpdated` INTEGER NOT NULL, 
                        PRIMARY KEY(`artifactId`)
                    )
                """.trimIndent())
            }
        }
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `artifact_review_evidence` (
                        `artifactId` TEXT NOT NULL, 
                        `durationMs` INTEGER NOT NULL, 
                        `coverageP1` INTEGER NOT NULL, 
                        `coverageP2` INTEGER NOT NULL, 
                        `cumulativeEffortMs` INTEGER NOT NULL, 
                        `furthestPositionMs` INTEGER NOT NULL,
                        `hasReachedEnd` INTEGER NOT NULL, 
                        `lastUpdated` INTEGER NOT NULL, 
                        PRIMARY KEY(`artifactId`)
                    )
                """.trimIndent())
            }
        }
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add column to current table first
                db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `totalTimeListenedMs` INTEGER NOT NULL DEFAULT 0")
                
                // 2. Recreate to change reviewCoverageBitmask type
                db.execSQL("CREATE TABLE `artifact_drafts_temp` (`id` TEXT NOT NULL, `localAudioPath` TEXT NOT NULL, `rawPcmPath` TEXT, `localTranscriptPath` TEXT, `waveformPath` TEXT, `title` TEXT, `description` TEXT, `emotion` TEXT, `isPublic` INTEGER NOT NULL, `tags` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `draftState` TEXT NOT NULL, `uploadStatus` TEXT NOT NULL, `syncState` TEXT NOT NULL, `uploadedBytes` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, `uploadSessionUri` TEXT, `uploadAttemptCount` INTEGER NOT NULL, `isEncrypted` INTEGER NOT NULL, `encryptionIv` TEXT, `checksum` TEXT, `approvalToken` TEXT, `deviceFingerprint` TEXT, `cooldownExpiry` INTEGER, `publishApprovalTimestamp` INTEGER, `revocationTimestamp` INTEGER, `emotionalRiskScore` REAL NOT NULL, `publishConfidence` REAL NOT NULL, `isEmotionalReady` INTEGER NOT NULL, `maxReviewPositionMs` INTEGER NOT NULL, `lastPlaybackPositionMs` INTEGER NOT NULL, `reviewCoverageBitmask` INTEGER NOT NULL, `coveragePart1` INTEGER NOT NULL DEFAULT 0, `coveragePart2` INTEGER NOT NULL DEFAULT 0, `totalTimeListenedMs` INTEGER NOT NULL, `isReviewLocked` INTEGER NOT NULL, `isListened` INTEGER NOT NULL, `isPlaybackEnded` INTEGER NOT NULL DEFAULT 0, `deviceId` TEXT, `transcriptionState` TEXT NOT NULL, `remoteArtifactId` TEXT, `emotionalTone` TEXT, `safetyAnalysis` TEXT, `interruptionReason` TEXT, `lastCheckpointTs` INTEGER NOT NULL, `durableBytes` INTEGER NOT NULL, `isCorrupted` INTEGER NOT NULL, `version` INTEGER NOT NULL, `mimeType` TEXT NOT NULL, `amplitudeData` TEXT NOT NULL, `reactionVisibility` TEXT, `uploadedAudioUrl` TEXT, `frozenTranscriptJson` TEXT, `frozenAudioPath` TEXT, `frozenMetadataJson` TEXT, `snapshotHash` TEXT, `transcriptSegmentsJson` TEXT, `sensitiveEntitiesJson` TEXT, PRIMARY KEY(`id`))")
                
                db.execSQL("INSERT INTO artifact_drafts_temp SELECT id, localAudioPath, rawPcmPath, localTranscriptPath, waveformPath, title, description, emotion, isPublic, tags, durationMs, createdAt, updatedAt, status, draftState, uploadStatus, syncState, uploadedBytes, totalBytes, uploadSessionUri, uploadAttemptCount, isEncrypted, encryptionIv, checksum, approvalToken, deviceFingerprint, cooldownExpiry, publishApprovalTimestamp, revocationTimestamp, emotionalRiskScore, publishConfidence, isEmotionalReady, maxReviewPositionMs, lastPlaybackPositionMs, 0, 0, 0, totalTimeListenedMs, isReviewLocked, isListened, 0, deviceId, transcriptionState, remoteArtifactId, emotionalTone, safetyAnalysis, interruptionReason, lastCheckpointTs, durableBytes, isCorrupted, version, mimeType, amplitudeData, reactionVisibility, uploadedAudioUrl, frozenTranscriptJson, frozenAudioPath, frozenMetadataJson, snapshotHash, transcriptSegmentsJson, sensitiveEntitiesJson FROM artifact_drafts")
                
                db.execSQL("DROP TABLE artifact_drafts")
                db.execSQL("ALTER TABLE artifact_drafts_temp RENAME TO artifact_drafts")
            }
        }
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No changes, just bumping version to force re-validation/destructive migration
            }
        }

        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No changes, just bumping version to resolve integrity check failure
            }
        }
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Structural change for artifact_drafts
                // Re-creating the table to ensure ALL columns match exactly and in order
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `artifact_drafts_new` (
                        `id` TEXT NOT NULL, 
                        `localAudioPath` TEXT NOT NULL, 
                        `rawPcmPath` TEXT, 
                        `localTranscriptPath` TEXT, 
                        `waveformPath` TEXT, 
                        `title` TEXT, 
                        `description` TEXT, 
                        `emotion` TEXT, 
                        `isPublic` INTEGER NOT NULL DEFAULT 1, 
                        `tags` TEXT NOT NULL, 
                        `durationMs` INTEGER NOT NULL, 
                        `createdAt` INTEGER NOT NULL, 
                        `updatedAt` INTEGER NOT NULL, 
                        `draftState` TEXT NOT NULL, 
                        `uploadStatus` TEXT NOT NULL, 
                        `syncState` TEXT NOT NULL, 
                        `uploadedBytes` INTEGER NOT NULL DEFAULT 0, 
                        `totalBytes` INTEGER NOT NULL DEFAULT 0, 
                        `uploadSessionUri` TEXT, 
                        `uploadAttemptCount` INTEGER NOT NULL DEFAULT 0, 
                        `isEncrypted` INTEGER NOT NULL DEFAULT 0, 
                        `encryptionIv` TEXT, 
                        `checksum` TEXT, 
                        `approvalToken` TEXT, 
                        `deviceFingerprint` TEXT, 
                        `cooldownExpiry` INTEGER, 
                        `publishApprovalTimestamp` INTEGER, 
                        `revocationTimestamp` INTEGER, 
                        `emotionalRiskScore` REAL NOT NULL DEFAULT 0.0, 
                        `publishConfidence` REAL NOT NULL DEFAULT 0.0, 
                        `isEmotionalReady` INTEGER NOT NULL DEFAULT 0, 
                        `maxReviewPositionMs` INTEGER NOT NULL DEFAULT 0, 
                        `lastPlaybackPositionMs` INTEGER NOT NULL DEFAULT 0, 
                        `reviewCoverageBitmask` TEXT, 
                        `coveragePart1` INTEGER NOT NULL DEFAULT 0,
                        `coveragePart2` INTEGER NOT NULL DEFAULT 0,
                        `isReviewLocked` INTEGER NOT NULL DEFAULT 1, 
                        `isListened` INTEGER NOT NULL DEFAULT 0, 
                        `isPlaybackEnded` INTEGER NOT NULL DEFAULT 0,
                        `deviceId` TEXT, 
                        `transcriptionState` TEXT NOT NULL DEFAULT 'IDLE', 
                        `remoteArtifactId` TEXT, 
                        `emotionalTone` TEXT, 
                        `safetyAnalysis` TEXT, 
                        `interruptionReason` TEXT, 
                        `lastCheckpointTs` INTEGER NOT NULL DEFAULT 0, 
                        `isCorrupted` INTEGER NOT NULL DEFAULT 0, 
                        `version` INTEGER NOT NULL DEFAULT 1, 
                        `mimeType` TEXT NOT NULL DEFAULT 'audio/mpeg', 
                        `amplitudeData` TEXT NOT NULL DEFAULT '[]', 
                        `reactionVisibility` TEXT, 
                        `frozenTranscriptJson` TEXT, 
                        `frozenAudioPath` TEXT, 
                        `frozenMetadataJson` TEXT, 
                        `snapshotHash` TEXT, 
                        `transcriptSegmentsJson` TEXT, 
                        `sensitiveEntitiesJson` TEXT, 
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())

                // Copy data from old artifact_drafts, mapping existing columns
                db.execSQL("""
                    INSERT INTO artifact_drafts_new (
                        id, localAudioPath, rawPcmPath, localTranscriptPath, waveformPath, title, description, 
                        emotion, isPublic, tags, durationMs, createdAt, updatedAt, 
                        draftState, uploadStatus, syncState, uploadedBytes, totalBytes, 
                        uploadSessionUri, uploadAttemptCount, isEncrypted, encryptionIv, 
                        checksum, approvalToken, deviceFingerprint, cooldownExpiry, 
                        publishApprovalTimestamp, revocationTimestamp, emotionalRiskScore, 
                        publishConfidence, isEmotionalReady, maxReviewPositionMs, 
                        lastPlaybackPositionMs, 0, 0, isListened, deviceId, transcriptionState, 
                        remoteArtifactId, lastCheckpointTs, isCorrupted, version, 
                        mimeType, amplitudeData, reactionVisibility, frozenTranscriptJson, 
                        frozenAudioPath, frozenMetadataJson, snapshotHash, 
                        transcriptSegmentsJson, sensitiveEntitiesJson
                    )
                    SELECT 
                        id, localAudioPath, NULL, localTranscriptPath, waveformPath, title, description, 
                        emotion, isPublic, tags, durationMs, createdAt, updatedAt, 
                        draftState, uploadStatus, syncState, uploadedBytes, totalBytes, 
                        uploadSessionUri, uploadAttemptCount, isEncrypted, encryptionIv, 
                        checksum, approvalToken, deviceFingerprint, cooldownExpiry, 
                        publishApprovalTimestamp, revocationTimestamp, emotionalRiskScore, 
                        publishConfidence, isEmotionalReady, maxReviewPositionMs, 
                        lastPlaybackPositionMs, 0, 0, isListened, deviceId, transcriptionState,
                        remoteArtifactId, lastCheckpointTs, isCorrupted, version, 
                        mimeType, amplitudeData, reactionVisibility, frozenTranscriptJson, 
                        frozenAudioPath, frozenMetadataJson, snapshotHash, 
                        transcriptSegmentsJson, sensitiveEntitiesJson
                    FROM artifact_drafts
                """.trimIndent())

                db.execSQL("DROP TABLE artifact_drafts")
                db.execSQL("ALTER TABLE artifact_drafts_new RENAME TO artifact_drafts")

                // 2. Structural change for queued_uploads
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `queued_uploads_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `userId` TEXT NOT NULL, 
                        `username` TEXT NOT NULL, 
                        `fileUri` TEXT NOT NULL, 
                        `title` TEXT NOT NULL, 
                        `isPublic` INTEGER NOT NULL, 
                        `duration` INTEGER NOT NULL, 
                        `emotion` TEXT NOT NULL, 
                        `emotionTag` TEXT NOT NULL DEFAULT '', 
                        `emotionConfidence` REAL NOT NULL DEFAULT 0.0, 
                        `avatarSeed` TEXT NOT NULL DEFAULT '', 
                        `prompt` TEXT NOT NULL, 
                        `redactionFilter` TEXT NOT NULL DEFAULT '', 
                        `amplitudeDataJson` TEXT NOT NULL, 
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO queued_uploads_new (
                        id, userId, username, fileUri, title, isPublic, duration, 
                        emotion, emotionTag, emotionConfidence, avatarSeed, prompt, redactionFilter, amplitudeDataJson, createdAt
                    )
                    SELECT 
                        id, userId, username, fileUri, title, isPublic, duration, 
                        emotion, emotionTag, emotionConfidence, avatarSeed, prompt, redactionFilter, amplitudeDataJson, createdAt
                    FROM queued_uploads
                """.trimIndent())

                db.execSQL("DROP TABLE queued_uploads")
                db.execSQL("ALTER TABLE queued_uploads_new RENAME TO queued_uploads")

                // 3. Structural change for prompts
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `prompts_new` (
                        `id` TEXT NOT NULL, 
                        `question` TEXT NOT NULL, 
                        `category` TEXT NOT NULL, 
                        `tone` TEXT NOT NULL, 
                        `mood` TEXT, 
                        `isFavorite` INTEGER NOT NULL DEFAULT 0, 
                        `usageCount` INTEGER NOT NULL DEFAULT 0, 
                        `lastUsedTimestamp` INTEGER NOT NULL DEFAULT 0, 
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())

                // Based on log history, the field might be 'text' or 'question' depending on when it crashed
                try {
                    db.execSQL("""
                        INSERT INTO prompts_new (id, question, category, tone, mood, isFavorite, usageCount, lastUsedTimestamp)
                        SELECT id, text, category, tone, NULL, isFavorite, usageCount, lastUsedTimestamp FROM prompts
                    """.trimIndent())
                } catch (e: Exception) {
                    db.execSQL("""
                        INSERT INTO prompts_new (id, question, category, tone, mood, isFavorite, usageCount, lastUsedTimestamp)
                        SELECT id, question, category, tone, mood, isFavorite, usageCount, lastUsedTimestamp FROM prompts
                    """.trimIndent())
                }

                db.execSQL("DROP TABLE prompts")
                db.execSQL("ALTER TABLE prompts_new RENAME TO prompts")
            }
        }

        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `durableBytes` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `playback_positions` (
                        `artifactId` TEXT NOT NULL, 
                        `positionMs` INTEGER NOT NULL, 
                        `updatedAt` INTEGER NOT NULL, 
                        PRIMARY KEY(`artifactId`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Recover from broken version 22 schema if rawPcmPath is missing
                val cursor = db.query("PRAGMA table_info(artifact_drafts)")
                var exists = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "rawPcmPath") {
                        exists = true
                        break
                    }
                }
                cursor.close()
                if (!exists) {
                    db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN rawPcmPath TEXT")
                }
            }
        }


        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Safety migration for those already on "version 20" but missing columns
                try {
                    db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN description TEXT")
                } catch (e: Exception) {
                    // Column might already exist
                }
                try {
                    db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN isListened INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {
                    // Column might already exist
                }
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN description TEXT")
                db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN isListened INTEGER NOT NULL DEFAULT 0")
            }
        }

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
