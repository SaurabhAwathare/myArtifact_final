# Implementation Plan - Fix Incomplete Room Migration 55 -> 56

The `MIGRATION_55_56` in `DatabaseMigrations.kt` is incomplete. It fails to add 5 out of the 10 new columns introduced in the `ArtifactEngagement` entity for version 56. This plan details the necessary `ALTER TABLE` statements to align the migration with the exported `56.json` schema.

## Proposed Changes

### [Component] Data Layer (Local Database)

#### [MODIFY] [DatabaseMigrations.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/DatabaseMigrations.kt)

Update `MIGRATION_55_56` to include the missing columns for the `artifact_engagement` table.

The updated `migrate` method for `MIGRATION_55_56` will include the following SQL statements:

```kotlin
val MIGRATION_55_56 = object : Migration(55, 56) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Update artifact_engagement with sync fields (Existing)
        db.execSQL("ALTER TABLE `artifact_engagement` ADD COLUMN `syncState` TEXT NOT NULL DEFAULT 'PENDING'")
        db.execSQL("ALTER TABLE `artifact_engagement` ADD COLUMN `lastSyncAttempt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `artifact_engagement` ADD COLUMN `lastSyncSuccess` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `artifact_engagement` ADD COLUMN `syncRetryCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `artifact_engagement` ADD COLUMN `lastSyncError` TEXT")

        // 2. Add backend-authoritative fields (Missing)
        db.execSQL("ALTER TABLE `artifact_engagement` ADD COLUMN `isCommentUnlocked` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `artifact_engagement` ADD COLUMN `unlockTimestamp` INTEGER")
        db.execSQL("ALTER TABLE `artifact_engagement` ADD COLUMN `engagementState` TEXT NOT NULL DEFAULT 'LOCKED'")
        db.execSQL("ALTER TABLE `artifact_engagement` ADD COLUMN `unlockReason` TEXT")
        db.execSQL("ALTER TABLE `artifact_engagement` ADD COLUMN `remoteUpdatedAt` INTEGER")

        // 3. Remove commentCount from artifacts table (Existing)
        // ... (rest of the existing logic for artifacts table)
    }
}
```

## Verification Plan

### Manual Verification
- Compare the final SQL statements in `DatabaseMigrations.kt` against the `artifact_engagement` definition in `app/schemas/com.saurabh.artifact.data.local.AppDatabase/56.json`.
- Ensure column names, types (INTEGER for Long/Boolean, TEXT for String), nullability, and default values match exactly.

| Column | Type in Kotlin | Type in SQLite | Nullable | Default |
| :--- | :--- | :--- | :--- | :--- |
| `isCommentUnlocked` | `Boolean` | `INTEGER` | No | `0` |
| `unlockTimestamp` | `Long?` | `INTEGER` | Yes | `NULL` |
| `engagementState` | `String` | `TEXT` | No | `'LOCKED'` |
| `unlockReason` | `String?` | `TEXT` | Yes | `NULL` |
| `remoteUpdatedAt` | `Long?` | `INTEGER` | Yes | `NULL` |

### Automated Tests
- The user has indicated this is for local development and the app is currently failing to start. Successful app startup after applying these changes will confirm the fix.
