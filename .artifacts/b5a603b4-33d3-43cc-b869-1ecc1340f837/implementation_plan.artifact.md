# Implementation Plan – Fix Room Database Identity Hash Mismatch (Version 55 → 56)

Objective: Resolve the Room identity hash mismatch by bringing the Version 55 → 56 migration into complete alignment with the exported Version 56 schema while preserving existing user data.

## User Review Required

> [!IMPORTANT]
> - **Schema Alignment**: Room identity hashes are extremely sensitive to column order, types, nullability, and default values. This fix will ensure `MIGRATION_55_56` exactly matches the `56.json` schema.
> - **Artifact Engagement Table**: We found that `MIGRATION_55_56` was missing the 5 new sync-related columns added in version 56.
> - **Artifacts Table**: We found a discrepancy where `MIGRATION_55_56` introduced `DEFAULT 0` for `reportCount` and `safetyConcernCount`, which are not present in the exported schema.

## Proposed Changes

### [Component] Database Layer

#### [MODIFY] [DatabaseMigrations.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/DatabaseMigrations.kt)
- Update `MIGRATION_55_56` to:
    1. Add missing columns to `artifact_engagement`:
        - `syncState` (TEXT, NOT NULL, DEFAULT 'PENDING')
        - `lastSyncAttempt` (INTEGER, NOT NULL, DEFAULT 0)
        - `lastSyncSuccess` (INTEGER, NOT NULL, DEFAULT 0)
        - `syncRetryCount` (INTEGER, NOT NULL, DEFAULT 0)
        - `lastSyncError` (TEXT, NULLABLE)
    2. Correct `artifacts_new` creation SQL:
        - Remove `DEFAULT 0` from `reportCount` and `safetyConcernCount`.
        - Ensure all types and constraints match `56.json` exactly.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to ensure compilation.
- (Optional) Room schema verification tests if available.

### Manual Verification
1. Verify the `CREATE TABLE` statements in `DatabaseMigrations.kt` against the `createSql` strings in `56.json`.
2. Confirm that every column in `56.json` for `artifact_engagement` and `artifacts` is accounted for in `MIGRATION_55_56`.
