# Fix Room Database Integrity Crash

The application is crashing with an `IllegalStateException` because the Room database schema in the code (version 32) does not match the schema on the device. This is due to recent changes in entity classes (`ArtifactReviewEvidence` and `ArtifactDraftEntity`) that were not reflected in the migrations.

## Proposed Changes

### Database Component

#### [AppDatabase.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/AppDatabase.kt)

- Bump version from 32 to 33.
- Add `MIGRATION_32_33` to handle missing columns:
    - `artifact_review_evidence`: Add `audioChecksum` (TEXT).
    - `artifact_drafts`: Add `coveragePart1` (INTEGER), `coveragePart2` (INTEGER), and `isPlaybackEnded` (INTEGER).
- Update historical migrations (`MIGRATION_31_32`, `MIGRATION_28_29`, `MIGRATION_21_22`) to ensure consistency for new installs/upgrades, although `MIGRATION_32_33` will be the primary fix for existing users on v32.

```kotlin
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Fix artifact_review_evidence
                db.execSQL("ALTER TABLE `artifact_review_evidence` ADD COLUMN `audioChecksum` TEXT NOT NULL DEFAULT ''")

                // Fix artifact_drafts (Missing columns that were added to entity but skipped in migrations)
                db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `coveragePart1` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `coveragePart2` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `artifact_drafts` ADD COLUMN `isPlaybackEnded` INTEGER NOT NULL DEFAULT 0")
            }
        }
```

#### [DatabaseModule.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/di/DatabaseModule.kt)

- Register `AppDatabase.MIGRATION_32_33` in the Room database builder.

## Verification Plan

### Automated Tests
- I will attempt to build the project to ensure the new migration is valid and Room can generate the new implementation.
- `gradle_build("app:assembleDebug")`

### Manual Verification
- Deploy the app to the device.
- Observe logcat to ensure the crash no longer occurs and the database is successfully opened.
- `deploy(ANDROID_APP, app, DEFAULT_ACTIVITY, RUN)`
- `read_logcat()`
