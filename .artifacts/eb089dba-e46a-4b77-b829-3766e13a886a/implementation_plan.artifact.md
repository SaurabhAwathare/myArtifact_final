# Production Database Baseline Plan (Revised)

This plan outlines the cleanup and consolidation required to establish a clean, production-ready database baseline for Artifact. We will treat the current version (**Version 60**) as the initial production baseline, removing only verified dead code and obsolete structures while preserving the migration infrastructure.

## User Review Required

> [!IMPORTANT]
> This revised plan preserves the current database version (**60**) and migration infrastructure as requested. Cleanup is strictly limited to code verified as "dead" (no reads/writes in the active application logic).

## Proposed Changes

### Database Baseline Strategy

The database will maintain its current version (**Version 60**). All historical migrations (1 to 59) will be archived/removed from the active migration chain to simplify the baseline, but the `DatabaseMigrations.kt` infrastructure will be preserved for future schema evolution.

---

### [Component] Local Data Layer

#### [DELETE] [QueuedUpload.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/QueuedUpload.kt) [NEW]
- Verified: Zero references for population or retrieval in the current source code. Used only for legacy maintenance pruning.

#### [DELETE] [QueuedUploadDao.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/QueuedUploadDao.kt) [NEW]
- Verified: No active code calls `insert`, `delete`, or `getAllQueuedUploads`.

#### [MODIFY] [AppDatabase.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/AppDatabase.kt)
- Keep `version = 60`.
- Remove `QueuedUpload::class` from `entities`.
- Remove `queuedUploadDao()` abstract function.

#### [MODIFY] [ArtifactDraftEntity.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/ArtifactDraftEntity.kt)
- **Verified Dead Fields** (Zero reads/writes found in active logic):
    - `maxReviewPositionMs` (Added in M49, unused in review logic)
    - `revocationTimestamp` (Added in M46, unused in approval/guard logic)
    - `emotionalRiskScore` (Legacy metric, no active consumers)
    - `publishConfidence` (Legacy metric, no active consumers)
    - `isEmotionalReady` (Legacy flag, no active consumers)
    - `snapshotHash` (Written but never verified/read)
    - `frozenMetadataJson` (Written but never verified/read)
    - `cooldownExpiry` (No active callers for `updateCooldown`)
    - `emotionalTone` (Associated with dead `updateTranscriptionResult`)
    - `localTranscriptPath` (Associated with dead `updateTranscriptionResult`)

#### [MODIFY] [ArtifactEntity.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/ArtifactEntity.kt)
- **Sigil Cleanup**: Remove `@ColumnInfo` name overrides for `authorAvatarSeed`, `authorAvatarColor`, and `authorAvatarConfigJson` to align DB columns with Kotlin property names (`authorSigilSeed`, etc.).
- **Consistency**: Add `commentCount: Long = 0L` to align with the domain `Artifact` model and the existing (but previously unmapped) DB column.

#### [MODIFY] [UserLocalEntity.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/UserLocalEntity.kt)
- **Sigil Cleanup**: Remove `@ColumnInfo` name overrides for `avatarSeed`, `avatarColor`, and `avatarConfigJson`.

#### [MODIFY] [DraftDao.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/DraftDao.kt)
- Remove dead methods:
    - `getPendingUploadsLegacy`
    - `updateTranscriptionResult`
    - `updatePrivacyResult`
    - `updateSafetyResult`
    - `updateEmotionalConfirmation`
    - `updateCooldown`

#### [MODIFY] [Converters.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/Converters.kt)
- Remove unused `EmotionResult` converters (used only by `QueuedUpload`).

#### [MODIFY] [DatabaseMaintenanceManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/DatabaseMaintenanceManager.kt)
- Remove pruning logic for `QueuedUpload`.

---

### [Component] Migration Management

#### [MODIFY] [DatabaseMigrations.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/DatabaseMigrations.kt)
- Clear all migration objects (`MIGRATION_1_2`, etc.) and the `ALL_MIGRATIONS` array.
- Retain the `object DatabaseMigrations` structure for future use.

## Verification Plan

### Automated Tests
- `gradlew :app:testDebugUnitTest` to verify that existing DAOs and Repositories (Artifact, Draft, Engagement) continue to function with the surgical field removals.
- Room Schema Verification: Ensure the exported `60.json` schema reflects the cleaned production baseline.

### Manual Verification
- Fresh install of the app.
- Execute full Recording -> Review -> Publish flow.
- Verify `isDraft` and `status` correctly transition from Draft to Active.
- Verify Sigil display is consistent across the app (proving the renaming didn't break mapping).
