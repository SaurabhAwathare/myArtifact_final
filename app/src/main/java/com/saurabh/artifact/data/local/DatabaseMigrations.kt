package com.saurabh.artifact.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Centrally managed database migrations for AppDatabase.
 * Each migration is documented with its business and technical purpose.
 */
object DatabaseMigrations {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifacts` ADD COLUMN `isPublic` INTEGER NOT NULL DEFAULT 1")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifacts` ADD COLUMN `emotion` TEXT NOT NULL DEFAULT 'NEUTRAL'")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifacts` ADD COLUMN `emotionTag` TEXT NOT NULL DEFAULT ''")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifacts` ADD COLUMN `playCount` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `artifacts` ADD COLUMN `reactionCount` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `isListened` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''")
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `waveformPath` TEXT")
        }
    }

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `rawPcmPath` TEXT")
        }
    }

    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `localTranscriptPath` TEXT")
        }
    }

    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `uploadedAudioUrl` TEXT")
        }
    }

    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `reviewCoverageBitmask` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `interruptionReason` TEXT")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `lastCheckpointTimestamp` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `durableBytes` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `isCorrupted` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `version` INTEGER NOT NULL DEFAULT 1")
        }
    }

    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `mimeType` TEXT NOT NULL DEFAULT 'audio/m4a'")
        }
    }

    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `amplitudeData` TEXT NOT NULL DEFAULT ''")
        }
    }

    val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `reactionVisibility` TEXT")
        }
    }

    val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `coveragePart1` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `coveragePart2` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `isPlaybackEnded` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `artifact_engagement` (
                    `artifactId` TEXT NOT NULL, 
                    `durationMs` INTEGER NOT NULL, 
                    `coverageP1` INTEGER NOT NULL, 
                    `coverageP2` INTEGER NOT NULL, 
                    `lastPositionMs` INTEGER NOT NULL, 
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
            // Replace bitmask fields with binary coverage
            db.execSQL("CREATE TABLE IF NOT EXISTS `artifact_engagement_new` (`artifactId` TEXT NOT NULL, `versionTag` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `audioChecksum` TEXT NOT NULL, `coverage` BLOB NOT NULL, `lastPositionMs` INTEGER NOT NULL, `furthestPositionMs` INTEGER NOT NULL, `hasReachedEnd` INTEGER NOT NULL, `lastUpdated` INTEGER NOT NULL, PRIMARY KEY(`artifactId`))")
            db.execSQL("INSERT INTO `artifact_engagement_new` (artifactId, versionTag, durationMs, audioChecksum, coverage, lastPositionMs, furthestPositionMs, hasReachedEnd, lastUpdated) SELECT artifactId, 'v1', durationMs, '', x'00', lastPositionMs, furthestPositionMs, hasReachedEnd, lastUpdated FROM artifact_engagement")
            db.execSQL("DROP TABLE `artifact_engagement`")
            db.execSQL("ALTER TABLE `artifact_engagement_new` RENAME TO `artifact_engagement`")
        }
    }

    val MIGRATION_32_33 = object : Migration(32, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifacts` ADD COLUMN `reportCount` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `artifacts` ADD COLUMN `safetyConcernCount` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `artifacts` ADD COLUMN `reporterIds` TEXT NOT NULL DEFAULT ''")
        }
    }

    val MIGRATION_33_34 = object : Migration(33, 34) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Re-create artifacts table with all currently required columns for stability
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `artifacts_new` (
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
                    `primaryStyle` TEXT, 
                    `emotionTag` TEXT NOT NULL, 
                    `playCount` INTEGER NOT NULL, 
                    `reactionCount` INTEGER NOT NULL, 
                    `commentCount` INTEGER NOT NULL, 
                    `reportCount` INTEGER NOT NULL DEFAULT 0, 
                    `safetyConcernCount` INTEGER NOT NULL DEFAULT 0, 
                    `reporterIds` TEXT NOT NULL, 
                    `amplitudeData` TEXT NOT NULL, 
                    `transcriptUrl` TEXT, 
                    `lastUpdated` INTEGER NOT NULL, 
                    PRIMARY KEY(`id`)
                )
            """.trimIndent()
            )

            db.execSQL(
                """
                INSERT INTO artifacts_new (
                    id, userId, authorAnonymousId, authorName, authorSigil, 
                    authorAvatarSeed, authorAvatarColor, authorAvatarConfigJson, 
                    audioUrl, createdAt, durationMs, title, description, 
                    emotion, primaryStyle, emotionTag, playCount, reactionCount, 
                    commentCount, reportCount, safetyConcernCount, reporterIds, 
                    amplitudeData, transcriptUrl, lastUpdated
                )
                SELECT 
                    id, userId, authorAnonymousId, authorName, authorSigil, 
                    authorAvatarSeed, authorAvatarColor, '', 
                    audioUrl, createdAt, durationMs, title, description, 
                    emotion, primaryStyle, emotionTag, playCount, reactionCount, 
                    0, reportCount, safetyConcernCount, reporterIds, 
                    amplitudeData, transcriptUrl, lastUpdated
                FROM artifacts
            """.trimIndent()
            )

            db.execSQL("DROP TABLE artifacts")
            db.execSQL("ALTER TABLE artifacts_new RENAME TO artifacts")
        }
    }

    val MIGRATION_34_35 = object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `reviewCompleted` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `titleCompleted` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `emotionCompleted` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `approvalCompleted` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_36_37 = object : Migration(36, 37) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Unify playback and engagement tracking
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `artifact_engagement_new` (
                    `artifactId` TEXT NOT NULL, 
                    `versionTag` TEXT NOT NULL, 
                    `durationMs` INTEGER NOT NULL, 
                    `audioChecksum` TEXT NOT NULL, 
                    `coverage` BLOB NOT NULL, 
                    `lastPositionMs` INTEGER NOT NULL, 
                    `furthestPositionMs` INTEGER NOT NULL, 
                    `hasReachedEnd` INTEGER NOT NULL, 
                    `lastUpdated` INTEGER NOT NULL, 
                    PRIMARY KEY(`artifactId`)
                )
            """.trimIndent()
            )

            // Seed with existing data
            db.execSQL(
                """
                INSERT OR IGNORE INTO artifact_engagement_new (
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

    val MIGRATION_37_38 = object : Migration(37, 38) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `frozenTranscriptJson` TEXT")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `frozenAudioPath` TEXT")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `frozenMetadataJson` TEXT")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `snapshotHash` TEXT")
        }
    }

    val MIGRATION_38_39 = object : Migration(38, 39) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `transcriptSegmentsJson` TEXT")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `sensitiveEntitiesJson` TEXT")
        }
    }

    val MIGRATION_39_40 = object : Migration(39, 40) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifacts` ADD COLUMN `toxicityScore` REAL NOT NULL DEFAULT 0.0")
        }
    }

    val MIGRATION_41_42 = object : Migration(41, 42) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `isEncrypted` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `encryptionIv` TEXT")
        }
    }

    val MIGRATION_42_43 = object : Migration(42, 43) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `approvalToken` TEXT")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `deviceFingerprint` TEXT")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `cooldownExpiry` INTEGER")
        }
    }

    val MIGRATION_45_46 = object : Migration(45, 46) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `publishApprovalTimestamp` INTEGER")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `revocationTimestamp` INTEGER")
        }
    }

    val MIGRATION_46_47 = object : Migration(46, 47) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `emotionalRiskScore` REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `publishConfidence` REAL NOT NULL DEFAULT 1.0")
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `isEmotionalReady` INTEGER NOT NULL DEFAULT 1")
        }
    }

    val MIGRATION_48_49 = object : Migration(48, 49) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `maxReviewPositionMs` INTEGER NOT NULL DEFAULT 0")
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

    val MIGRATION_52_53 = object : Migration(52, 53) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `dead_letter_interactions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                    `originalId` INTEGER NOT NULL, 
                    `artifactId` TEXT NOT NULL, 
                    `interactionType` TEXT NOT NULL, 
                    `action` TEXT NOT NULL, 
                    `metadata` TEXT, 
                    `createdAt` INTEGER NOT NULL, 
                    `correlationId` TEXT NOT NULL, 
                    `failedAt` INTEGER NOT NULL, 
                    `failureReason` TEXT, 
                    `failureType` TEXT NOT NULL, 
                    `retryCount` INTEGER NOT NULL
                )
            """.trimIndent()
            )
        }
    }

    val MIGRATION_53_54 = object : Migration(53, 54) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `pending_interactions` ADD COLUMN `userId` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `dead_letter_interactions` ADD COLUMN `userId` TEXT NOT NULL DEFAULT ''")
        }
    }

    val MIGRATION_54_55 = object : Migration(54, 55) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `lastRecoveryAttemptAt` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_55_56 = object : Migration(55, 56) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Remove commentCount from artifacts table
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `artifacts_new` (
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
                    `primaryStyle` TEXT, 
                    `emotionTag` TEXT NOT NULL, 
                    `playCount` INTEGER NOT NULL, 
                    `reactionCount` INTEGER NOT NULL, 
                    `reportCount` INTEGER NOT NULL DEFAULT 0, 
                    `safetyConcernCount` INTEGER NOT NULL DEFAULT 0, 
                    `reporterIds` TEXT NOT NULL, 
                    `amplitudeData` TEXT NOT NULL, 
                    `transcriptUrl` TEXT, 
                    `lastUpdated` INTEGER NOT NULL, 
                    PRIMARY KEY(`id`)
                )
            """.trimIndent()
            )

            db.execSQL(
                """
                INSERT INTO artifacts_new (
                    id, userId, authorAnonymousId, authorName, authorSigil, 
                    authorAvatarSeed, authorAvatarColor, authorAvatarConfigJson, 
                    audioUrl, createdAt, durationMs, title, description, 
                    emotion, primaryStyle, emotionTag, playCount, reactionCount, 
                    reportCount, safetyConcernCount, reporterIds, 
                    amplitudeData, transcriptUrl, lastUpdated
                )
                SELECT 
                    id, userId, authorAnonymousId, authorName, authorSigil, 
                    authorAvatarSeed, authorAvatarColor, authorAvatarConfigJson, 
                    audioUrl, createdAt, durationMs, title, description, 
                    emotion, primaryStyle, emotionTag, playCount, reactionCount, 
                    reportCount, safetyConcernCount, reporterIds, 
                    amplitudeData, transcriptUrl, lastUpdated
                FROM artifacts
            """.trimIndent()
            )

            db.execSQL("DROP TABLE artifacts")
            db.execSQL("ALTER TABLE artifacts_new RENAME TO artifacts")
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
        MIGRATION_51_52,
        MIGRATION_52_53,
        MIGRATION_53_54,
        MIGRATION_54_55,
        MIGRATION_55_56
    )
}
