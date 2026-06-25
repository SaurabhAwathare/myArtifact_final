package com.saurabh.artifact.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE queued_uploads ADD COLUMN createdAt INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE recording_drafts ADD COLUMN status TEXT NOT NULL DEFAULT 'IDLE'")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE queued_uploads ADD COLUMN prompt TEXT NOT NULL DEFAULT ''")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
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
            """.trimIndent()
            )
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

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN reactionVisibility TEXT")
        }
    }

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN emotionalRiskScore REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN publishConfidence REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN isEmotionalReady INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
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
            """.trimIndent()
            )
        }
    }

    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN description TEXT")
            db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN isListened INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Safety migration for those already on "version 20" but missing columns
            try {
                db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN description TEXT")
            } catch (_: Exception) {
                // Column might already exist
            }
            try {
                db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN isListened INTEGER NOT NULL DEFAULT 0")
            } catch (_: Exception) {
                // Column might already exist
            }
        }
    }

    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. Structural change for artifact_drafts
            db.execSQL(
                """
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
                    `lastCheckpointTimestamp` INTEGER NOT NULL DEFAULT 0, 
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
            """.trimIndent()
            )

            db.execSQL(
                """
                INSERT INTO artifact_drafts_new (
                    id, localAudioPath, rawPcmPath, localTranscriptPath, waveformPath, title, description, 
                    emotion, isPublic, tags, durationMs, createdAt, updatedAt, 
                    draftState, uploadStatus, syncState, uploadedBytes, totalBytes, 
                    uploadSessionUri, uploadAttemptCount, isEncrypted, encryptionIv, 
                    checksum, approvalToken, deviceFingerprint, cooldownExpiry, 
                    publishApprovalTimestamp, revocationTimestamp, emotionalRiskScore, 
                    publishConfidence, isEmotionalReady, maxReviewPositionMs, 
                    lastPlaybackPositionMs, isListened, deviceId, transcriptionState, 
                    remoteArtifactId, lastCheckpointTimestamp, isCorrupted, version,
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
                    lastPlaybackPositionMs, isListened, deviceId, transcriptionState, 
                    remoteArtifactId, lastCheckpointTimestamp, isCorrupted, version, 
                    mimeType, amplitudeData, reactionVisibility, frozenTranscriptJson, 
                    frozenAudioPath, frozenMetadataJson, snapshotHash, 
                    transcriptSegmentsJson, sensitiveEntitiesJson
                FROM artifact_drafts
            """.trimIndent()
            )

            db.execSQL("DROP TABLE artifact_drafts")
            db.execSQL("ALTER TABLE artifact_drafts_new RENAME TO artifact_drafts")

            // 2. Structural change for queued_uploads
            db.execSQL(
                """
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
            """.trimIndent()
            )

            db.execSQL(
                """
                INSERT INTO queued_uploads_new (
                    id, userId, username, fileUri, title, isPublic, duration, 
                    emotion, emotionTag, emotionConfidence, avatarSeed, prompt, redactionFilter, amplitudeDataJson, createdAt
                )
                SELECT 
                    id, userId, username, fileUri, title, isPublic, duration, 
                    emotion, emotionTag, emotionConfidence, avatarSeed, prompt, redactionFilter, amplitudeDataJson, createdAt
                FROM queued_uploads
            """.trimIndent()
            )

            db.execSQL("DROP TABLE queued_uploads")
            db.execSQL("ALTER TABLE queued_uploads_new RENAME TO queued_uploads")

            // 3. Structural change for prompts
            db.execSQL(
                """
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
            """.trimIndent()
            )

            try {
                db.execSQL(
                    """
                    INSERT INTO prompts_new (id, question, category, tone, mood, isFavorite, usageCount, lastUsedTimestamp)
                    SELECT id, text, category, tone, NULL, isFavorite, usageCount, lastUsedTimestamp FROM prompts
                """.trimIndent()
                )
            } catch (_: Exception) {
                db.execSQL(
                    """
                    INSERT INTO prompts_new (id, question, category, tone, mood, isFavorite, usageCount, lastUsedTimestamp)
                    SELECT id, question, category, tone, mood, isFavorite, usageCount, lastUsedTimestamp FROM prompts
                """.trimIndent()
                )
            }

            db.execSQL("DROP TABLE prompts")
            db.execSQL("ALTER TABLE prompts_new RENAME TO prompts")
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

    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `durableBytes` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `playback_positions` (
                    `artifactId` TEXT NOT NULL, 
                    `positionMs` INTEGER NOT NULL, 
                    `updatedAt` INTEGER NOT NULL, 
                    PRIMARY KEY(`artifactId`)
                )
            """.trimIndent()
            )
        }
    }

    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No changes, just bumping version to resolve integrity check failure
        }
    }

    val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No changes, just bumping version to force re-validation/destructive migration
        }
    }

    val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. Add column to current table first
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `totalTimeListenedMs` INTEGER NOT NULL DEFAULT 0")

            // 2. Recreate to change reviewCoverageBitmask type
            db.execSQL("CREATE TABLE `artifact_drafts_temp` (`id` TEXT NOT NULL, `localAudioPath` TEXT NOT NULL, `rawPcmPath` TEXT, `localTranscriptPath` TEXT, `waveformPath` TEXT, `title` TEXT, `description` TEXT, `emotion` TEXT, `isPublic` INTEGER NOT NULL, `tags` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `draftState` TEXT NOT NULL, `uploadStatus` TEXT NOT NULL, `syncState` TEXT NOT NULL, `uploadedBytes` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, `uploadSessionUri` TEXT, `uploadAttemptCount` INTEGER NOT NULL, `isEncrypted` INTEGER NOT NULL, `encryptionIv` TEXT, `checksum` TEXT, `approvalToken` TEXT, `deviceFingerprint` TEXT, `cooldownExpiry` INTEGER, `publishApprovalTimestamp` INTEGER, `revocationTimestamp` INTEGER, `emotionalRiskScore` REAL NOT NULL, `publishConfidence` REAL NOT NULL, `isEmotionalReady` INTEGER NOT NULL, `maxReviewPositionMs` INTEGER NOT NULL, `lastPlaybackPositionMs` INTEGER NOT NULL, `reviewCoverageBitmask` INTEGER NOT NULL, `coveragePart1` INTEGER NOT NULL DEFAULT 0, `coveragePart2` INTEGER NOT NULL DEFAULT 0, `totalTimeListenedMs` INTEGER NOT NULL, `isReviewLocked` INTEGER NOT NULL, `isListened` INTEGER NOT NULL, `isPlaybackEnded` INTEGER NOT NULL DEFAULT 0, `deviceId` TEXT, `transcriptionState` TEXT NOT NULL, `remoteArtifactId` TEXT, `emotionalTone` TEXT, `safetyAnalysis` TEXT, `interruptionReason` TEXT, `lastCheckpointTimestamp` INTEGER NOT NULL, `durableBytes` INTEGER NOT NULL, `isCorrupted` INTEGER NOT NULL, `version` INTEGER NOT NULL, `mimeType` TEXT NOT NULL, `amplitudeData` TEXT NOT NULL, `reactionVisibility` TEXT, `uploadedAudioUrl` TEXT, `frozenTranscriptJson` TEXT, `frozenAudioPath` TEXT, `frozenMetadataJson` TEXT, `snapshotHash` TEXT, `transcriptSegmentsJson` TEXT, `sensitiveEntitiesJson` TEXT, PRIMARY KEY(`id`))")

            db.execSQL("INSERT INTO artifact_drafts_temp SELECT id, localAudioPath, rawPcmPath, localTranscriptPath, waveformPath, title, description, emotion, isPublic, tags, durationMs, createdAt, updatedAt, status, draftState, uploadStatus, syncState, uploadedBytes, totalBytes, uploadSessionUri, uploadAttemptCount, isEncrypted, encryptionIv, checksum, approvalToken, deviceFingerprint, cooldownExpiry, publishApprovalTimestamp, revocationTimestamp, emotionalRiskScore, publishConfidence, isEmotionalReady, maxReviewPositionMs, lastPlaybackPositionMs, 0, 0, 0, totalTimeListenedMs, isReviewLocked, isListened, 0, deviceId, transcriptionState, remoteArtifactId, emotionalTone, safetyAnalysis, interruptionReason, lastCheckpointTimestamp, durableBytes, isCorrupted, version, mimeType, amplitudeData, reactionVisibility, uploadedAudioUrl, frozenTranscriptJson, frozenAudioPath, frozenMetadataJson, snapshotHash, transcriptSegmentsJson, sensitiveEntitiesJson FROM artifact_drafts")

            db.execSQL("DROP TABLE artifact_drafts")
            db.execSQL("ALTER TABLE artifact_drafts_temp RENAME TO artifact_drafts")
        }
    }

    val MIGRATION_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
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
            """.trimIndent()
            )
        }
    }

    val MIGRATION_31_32 = object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Recreate the table with new structure
            db.execSQL("DROP TABLE IF EXISTS `artifact_review_evidence`")
            db.execSQL(
                """
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
            """.trimIndent()
            )
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

    val MIGRATION_33_34 = object : Migration(33, 34) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
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
            """.trimIndent()
            )
        }
    }

    val MIGRATION_34_35 = object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `upload_tasks` (
                    `draftId` TEXT NOT NULL, 
                    `workerId` TEXT, 
                    `status` TEXT NOT NULL, 
                    `uploadedBytes` INTEGER NOT NULL, 
                    `totalBytes` INTEGER NOT NULL, 
                    `sessionUri` TEXT, 
                    `audioUrl` TEXT, 
                    `lastUpdated` INTEGER NOT NULL, 
                    PRIMARY KEY(`draftId`), 
                    FOREIGN KEY(`draftId`) REFERENCES `artifact_drafts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE 
                )
            """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_tasks_draftId` ON `upload_tasks` (`draftId`)")
        }
    }

    val MIGRATION_36_37 = object : Migration(36, 37) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `artifact_engagement` (
                    `artifactId` TEXT NOT NULL, 
                    `versionTag` TEXT NOT NULL,
                    `durationMs` INTEGER NOT NULL, 
                    `audioChecksum` TEXT NOT NULL,
                    `coverage` BLOB NOT NULL, 
                    `effortMap` TEXT NOT NULL, 
                    `lastPositionMs` INTEGER NOT NULL, 
                    `furthestPositionMs` INTEGER NOT NULL, 
                    `hasReachedEnd` INTEGER NOT NULL, 
                    `lastUpdated` INTEGER NOT NULL, 
                    PRIMARY KEY(`artifactId`)
                )
            """.trimIndent()
            )

            // Migrate from artifact_review_evidence
            db.execSQL(
                """
                INSERT OR IGNORE INTO artifact_engagement (
                    artifactId, versionTag, durationMs, audioChecksum, coverage, 
                    effortMap, lastPositionMs, furthestPositionMs, hasReachedEnd, lastUpdated
                )
                SELECT 
                    artifactId, versionTag, durationMs, audioChecksum, coverage, 
                    effortMap, 0, furthestPositionMs, hasReachedEnd, lastUpdated
                FROM artifact_review_evidence
            """.trimIndent()
            )

            // Update lastPositionMs from playback_positions
            db.execSQL(
                """
                UPDATE artifact_engagement 
                SET lastPositionMs = (
                    SELECT positionMs FROM playback_positions 
                    WHERE playback_positions.artifactId = artifact_engagement.artifactId
                )
                WHERE EXISTS (
                    SELECT 1 FROM playback_positions 
                    WHERE playback_positions.artifactId = artifact_engagement.artifactId
                )
            """.trimIndent()
            )

            // Insert remaining from playback_positions
            db.execSQL(
                """
                INSERT OR IGNORE INTO artifact_engagement (
                    artifactId, versionTag, durationMs, audioChecksum, coverage, 
                    effortMap, lastPositionMs, furthestPositionMs, hasReachedEnd, lastUpdated
                )
                SELECT 
                    artifactId, 'v1', 0, '', x'00', '{}', positionMs, 0, 0, updatedAt
                FROM playback_positions
            """.trimIndent()
            )

            db.execSQL("DROP TABLE IF EXISTS `playback_positions`")
            db.execSQL("DROP TABLE IF EXISTS `artifact_review_evidence`")
        }
    }

    val MIGRATION_37_38 = object : Migration(37, 38) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifacts` ADD COLUMN `transcriptUrl` TEXT")
        }
    }

    val MIGRATION_38_39 = object : Migration(38, 39) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifacts` ADD COLUMN `lastUpdated` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_39_40 = object : Migration(39, 40) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifacts` ADD COLUMN `reportCount` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `artifacts` ADD COLUMN `reporterIds` TEXT NOT NULL DEFAULT '[]'")
        }
    }

    val MIGRATION_41_42 = object : Migration(41, 42) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `pending_interactions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                    `artifactId` TEXT NOT NULL, 
                    `interactionType` TEXT NOT NULL, 
                    `action` TEXT NOT NULL, 
                    `metadata` TEXT, 
                    `createdAt` INTEGER NOT NULL
                )
            """.trimIndent()
            )
        }
    }

    val MIGRATION_42_43 = object : Migration(42, 43) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Empty migration to avoid destructive migration between identical schemas
        }
    }

    val MIGRATION_45_46 = object : Migration(45, 46) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `current_user_profile` (
                    `id` TEXT NOT NULL, 
                    `anonymousId` TEXT NOT NULL, 
                    `anonymousName` TEXT NOT NULL, 
                    `anonymousSigil` TEXT NOT NULL, 
                    `avatarSeed` TEXT NOT NULL, 
                    `avatarColor` TEXT NOT NULL, 
                    `avatarConfigJson` TEXT NOT NULL, 
                    `lastUpdated` INTEGER NOT NULL, 
                    PRIMARY KEY(`id`)
                )
            """.trimIndent()
            )
        }
    }

    val MIGRATION_46_47 = object : Migration(46, 47) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Version 46 schema was actually missing safetyConcernCount and primaryStyle 
            // from the artifacts table compared to the entity.
            
            // Check artifacts table for safetyConcernCount
            val cursorArtifacts = db.query("PRAGMA table_info(`artifacts`)")
            var hasSafetyConcernCount = false
            var hasPrimaryStyle = false
            while (cursorArtifacts.moveToNext()) {
                val name = cursorArtifacts.getString(cursorArtifacts.getColumnIndexOrThrow("name"))
                if (name == "safetyConcernCount") hasSafetyConcernCount = true
                if (name == "primaryStyle") hasPrimaryStyle = true
            }
            cursorArtifacts.close()
            
            if (!hasSafetyConcernCount) {
                db.execSQL("ALTER TABLE `artifacts` ADD COLUMN `safetyConcernCount` INTEGER NOT NULL DEFAULT 0")
            }
            if (!hasPrimaryStyle) {
                db.execSQL("ALTER TABLE `artifacts` ADD COLUMN `primaryStyle` TEXT")
            }

            // Check upload_tasks for owner
            val cursorUploadTasks = db.query("PRAGMA table_info(`upload_tasks`)")
            var hasOwner = false
            while (cursorUploadTasks.moveToNext()) {
                if (cursorUploadTasks.getString(cursorUploadTasks.getColumnIndexOrThrow("name")) == "owner") {
                    hasOwner = true
                }
            }
            cursorUploadTasks.close()
            if (!hasOwner) {
                db.execSQL("ALTER TABLE `upload_tasks` ADD COLUMN `owner` TEXT")
            }
        }
    }

    val MIGRATION_48_49 = object : Migration(48, 49) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. Update artifact_drafts
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `studioStep` TEXT NOT NULL DEFAULT 'REVIEW'")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `reviewCompleted` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `titleCompleted` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `emotionCompleted` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `approvalCompleted` INTEGER NOT NULL DEFAULT 0")

            // 2. Refactor artifact_engagement to remove effortMap
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `artifact_engagement_new` (
                    `artifactId` TEXT NOT NULL, 
                    `versionTag` TEXT NOT NULL, 
                    `durationMs` INTEGER NOT NULL, 
                    `audioChecksum` TEXT NOT NULL DEFAULT '', 
                    `coverage` BLOB NOT NULL, 
                    `lastPositionMs` INTEGER NOT NULL, 
                    `furthestPositionMs` INTEGER NOT NULL, 
                    `hasReachedEnd` INTEGER NOT NULL, 
                    `lastUpdated` INTEGER NOT NULL DEFAULT 0, 
                    PRIMARY KEY(`artifactId`)
                )
            """.trimIndent()
            )

            db.execSQL(
                """
                INSERT INTO artifact_engagement_new (
                    artifactId, versionTag, durationMs, audioChecksum, 
                    coverage, lastPositionMs, furthestPositionMs, hasReachedEnd, lastUpdated
                )
                SELECT 
                    artifactId, versionTag, durationMs, audioChecksum, 
                    coverage, lastPositionMs, furthestPositionMs, hasReachedEnd, lastUpdated
                FROM artifact_engagement
            """.trimIndent()
            )

            db.execSQL("DROP TABLE artifact_engagement")
            db.execSQL("ALTER TABLE artifact_engagement_new RENAME TO artifact_engagement")
        }
    }

    val MIGRATION_49_50 = object : Migration(49, 50) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `isDismissed` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_50_51 = object : Migration(50, 51) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Fix for the schema mismatch at version 50.
            // studioStep was removed from the entity but MIGRATION_49_50 didn't remove it from DB.
            
            // 1. Create temporary table with the correct schema (matching version 51/current entity)
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
                    `isPublic` INTEGER NOT NULL, 
                    `isListened` INTEGER NOT NULL, 
                    `tags` TEXT NOT NULL, 
                    `durationMs` INTEGER NOT NULL, 
                    `createdAt` INTEGER NOT NULL, 
                    `updatedAt` INTEGER NOT NULL, 
                    `status` TEXT NOT NULL, 
                    `lifecycle` TEXT NOT NULL, 
                    `uploadedBytes` INTEGER NOT NULL, 
                    `totalBytes` INTEGER NOT NULL, 
                    `uploadSessionUri` TEXT, 
                    `uploadAttemptCount` INTEGER NOT NULL, 
                    `isEncrypted` INTEGER NOT NULL, 
                    `encryptionIv` TEXT, 
                    `checksum` TEXT, 
                    `approvalToken` TEXT, 
                    `deviceFingerprint` TEXT, 
                    `cooldownExpiry` INTEGER, 
                    `publishApprovalTimestamp` INTEGER, 
                    `revocationTimestamp` INTEGER, 
                    `emotionalRiskScore` REAL NOT NULL, 
                    `publishConfidence` REAL NOT NULL, 
                    `isEmotionalReady` INTEGER NOT NULL, 
                    `maxReviewPositionMs` INTEGER NOT NULL, 
                    `reviewProgress` REAL NOT NULL, 
                    `deviceId` TEXT, 
                    `transcriptionState` TEXT NOT NULL, 
                    `remoteArtifactId` TEXT, 
                    `emotionalTone` TEXT, 
                    `primaryStyle` TEXT, 
                    `safetyAnalysis` TEXT, 
                    `interruptionReason` TEXT, 
                    `lastCheckpointTimestamp` INTEGER NOT NULL, 
                    `durableBytes` INTEGER NOT NULL, 
                    `isCorrupted` INTEGER NOT NULL, 
                    `version` INTEGER NOT NULL, 
                    `mimeType` TEXT NOT NULL, 
                    `amplitudeData` TEXT NOT NULL, 
                    `reactionVisibility` TEXT, 
                    `uploadedAudioUrl` TEXT, 
                    `frozenTranscriptJson` TEXT, 
                    `frozenAudioPath` TEXT, 
                    `frozenMetadataJson` TEXT, 
                    `snapshotHash` TEXT, 
                    `transcriptSegmentsJson` TEXT, 
                    `sensitiveEntitiesJson` TEXT, 
                    `reviewCompleted` INTEGER NOT NULL, 
                    `titleCompleted` INTEGER NOT NULL, 
                    `emotionCompleted` INTEGER NOT NULL, 
                    `approvalCompleted` INTEGER NOT NULL, 
                    `isDismissed` INTEGER NOT NULL, 
                    PRIMARY KEY(`id`)
                )
            """.trimIndent())

            // 2. Copy data from old table to new table
            db.execSQL("""
                INSERT INTO artifact_drafts_new (
                    id, localAudioPath, rawPcmPath, localTranscriptPath, waveformPath, title, description, emotion, 
                    isPublic, isListened, tags, durationMs, createdAt, updatedAt, status, lifecycle, 
                    uploadedBytes, totalBytes, uploadSessionUri, uploadAttemptCount, isEncrypted, 
                    encryptionIv, checksum, approvalToken, deviceFingerprint, cooldownExpiry, 
                    publishApprovalTimestamp, revocationTimestamp, emotionalRiskScore, publishConfidence, 
                    isEmotionalReady, maxReviewPositionMs, reviewProgress, deviceId, transcriptionState, 
                    remoteArtifactId, emotionalTone, primaryStyle, safetyAnalysis, interruptionReason, 
                    lastCheckpointTimestamp, durableBytes, isCorrupted, version, mimeType, 
                    amplitudeData, reactionVisibility, uploadedAudioUrl, frozenTranscriptJson, 
                    frozenAudioPath, frozenMetadataJson, snapshotHash, transcriptSegmentsJson, 
                    sensitiveEntitiesJson, reviewCompleted, titleCompleted, emotionCompleted, 
                    approvalCompleted, isDismissed
                )
                SELECT 
                    id, localAudioPath, rawPcmPath, localTranscriptPath, waveformPath, title, description, emotion, 
                    isPublic, isListened, tags, durationMs, createdAt, updatedAt, status, lifecycle, 
                    uploadedBytes, totalBytes, uploadSessionUri, uploadAttemptCount, isEncrypted, 
                    encryptionIv, checksum, approvalToken, deviceFingerprint, cooldownExpiry, 
                    publishApprovalTimestamp, revocationTimestamp, emotionalRiskScore, publishConfidence, 
                    isEmotionalReady, maxReviewPositionMs, reviewProgress, deviceId, transcriptionState, 
                    remoteArtifactId, emotionalTone, primaryStyle, safetyAnalysis, interruptionReason, 
                    lastCheckpointTimestamp, durableBytes, isCorrupted, version, mimeType, 
                    amplitudeData, reactionVisibility, uploadedAudioUrl, frozenTranscriptJson, 
                    frozenAudioPath, frozenMetadataJson, snapshotHash, transcriptSegmentsJson, 
                    sensitiveEntitiesJson, reviewCompleted, titleCompleted, emotionCompleted, 
                    approvalCompleted, isDismissed
                FROM artifact_drafts
            """.trimIndent())

            // 3. Swap tables
            db.execSQL("DROP TABLE artifact_drafts")
            db.execSQL("ALTER TABLE artifact_drafts_new RENAME TO artifact_drafts")
        }
    }

    val MIGRATION_51_52 = object : Migration(51, 52) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Update pending_interactions to include observability columns
            db.execSQL("ALTER TABLE `pending_interactions` ADD COLUMN `correlationId` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `pending_interactions` ADD COLUMN `workerId` TEXT")
            db.execSQL("ALTER TABLE `pending_interactions` ADD COLUMN `retryCount` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `pending_interactions` ADD COLUMN `lastError` TEXT")
        }
    }

    val ALL_MIGRATIONS = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_14_15,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22,
        MIGRATION_22_23,
        MIGRATION_23_24,
        MIGRATION_24_25,
        MIGRATION_25_26,
        MIGRATION_26_27,
        MIGRATION_28_29,
        MIGRATION_30_31,
        MIGRATION_31_32,
        MIGRATION_32_33,
        MIGRATION_33_34,
        MIGRATION_34_35,
        MIGRATION_36_37,
        MIGRATION_37_38,
        MIGRATION_38_39,
        MIGRATION_39_40,
        MIGRATION_41_42,
        MIGRATION_42_43,
        MIGRATION_45_46,
        MIGRATION_46_47,
        MIGRATION_48_49,
        MIGRATION_49_50,
        MIGRATION_50_51,
        MIGRATION_51_52
    )
}
