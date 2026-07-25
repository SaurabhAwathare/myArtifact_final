# Review Segmentation Architecture Migration (Phase 13)

Implement a versioned Review Segmentation architecture to move from legacy bucketed segmentation (Version 1) to a fixed 1000ms segmentation (Version 2). This change ensures consistent validation across different audio durations while maintaining full backward compatibility with existing engagement data.

## User Review Required

> [!IMPORTANT]
> This architectural change involves a database migration (Version 58 to 59). Ensure that the build system is prepared for schema exports if required.

> [!NOTE]
> Existing sessions will remain on Version 1. Only new sessions will use Version 2 (1000ms fixed segments).

## Proposed Changes

### Domain & Business Logic

#### [NEW] [ReviewTrackingVersion.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/review/ReviewTrackingVersion.kt)
- Define `ReviewTrackingVersion` enum: `LEGACY_BUCKETED = 1`, `FIXED_ONE_SECOND = 2`.
- Add `CURRENT = FIXED_ONE_SECOND` constant.

#### [MODIFY] [EngagementEvidence.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/review/EngagementEvidence.kt)
- Add `reviewTrackingVersion: ReviewTrackingVersion = ReviewTrackingVersion.LEGACY_BUCKETED`.
- Add `segmentSizeMs: Long = 0L` as diagnostic metadata.

#### [MODIFY] [ReviewPolicy.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/review/ReviewPolicy.kt)
- Update `getSegmentSizeMs(durationMs: Long, version: ReviewTrackingVersion)` to be the authoritative source for segment derivation.

#### [MODIFY] [PublishingReviewPolicy.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/review/publishing/PublishingReviewPolicy.kt)
- Update `getSegmentSizeMs` to accept `ReviewTrackingVersion`.

#### [MODIFY] [PublishingReviewValidator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/review/publishing/PublishingReviewValidator.kt)
- Dispatch validation logic using `evidence.reviewTrackingVersion`.
- Derive `segmentSize` from `policy.getSegmentSizeMs(evidence.durationMs, evidence.reviewTrackingVersion)`.

### Data Layer

#### [MODIFY] [ArtifactEngagement.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/ArtifactEngagement.kt)
- Add `reviewTrackingVersion: Int` and `segmentSizeMs: Long` columns.
- Update `equals` and `hashCode`.

#### [MODIFY] [Converters.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/Converters.kt)
- Add `TypeConverter` for `ReviewTrackingVersion`.

#### [MODIFY] [EngagementRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/EngagementRepository.kt)
- Update `toDomain()` and `toEntity()` mapping functions to handle new versioning fields.

#### [MODIFY] [DatabaseMigrations.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/DatabaseMigrations.kt)
- Add `MIGRATION_58_59` to add `reviewTrackingVersion` and `segmentSizeMs` columns to `artifact_engagement`.

#### [MODIFY] [AppDatabase.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/AppDatabase.kt)
- Increment version to `59`.

### Audio & Tracking

#### [MODIFY] [ReviewAuthorityService.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/ReviewAuthorityService.kt)
- In `initializeSession`, set `reviewTrackingVersion = ReviewTrackingVersion.CURRENT` for new sessions.
- Derive `segmentSizeMs` using `policy.getSegmentSizeMs(...)`.

#### [MODIFY] [ReviewTracker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/validation/ReviewTracker.kt)
- Update `DefaultReviewTracker` to use `currentEvidence.reviewTrackingVersion` when requesting segment size.

### Sync Layer

#### [MODIFY] [FirestoreEngagementRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/FirestoreEngagementRepository.kt)
- Update `uploadEngagement` payload to include `reviewTrackingVersion` and `segmentSizeMs`.

## Verification Plan

### Automated Tests
- **Unit Tests**:
    - `ReviewTrackerTest.kt`: Add cases for `FIXED_ONE_SECOND` version.
    - `PublishingReviewValidatorTest.kt`: Verify version-based dispatching and segment derivation.
- **Database Migration Test**: Verify schema upgrade from 58 to 59.

### Manual Verification
- Deploy to device/emulator.
- Start a new review session: Verify it uses Version 2 (1s segments).
- Resume an old session: Verify it remains on Version 1.
- Complete review: Verify publishing still works with 95% coverage.
