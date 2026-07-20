# Implementation Plan - Restore Firestore → Room → UI Data Integrity (Updated)

Fix the data integrity issue where `status` and `isDraft` fields are lost between Firestore, Room, and the UI. This update incorporates recommendations for type-safe status storage and atomic Firestore updates.

## Proposed Changes

### Data Layer

#### [MODIFY] [ArtifactEntity.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/ArtifactEntity.kt)
- Add `status: ArtifactStatus` and `isDraft: Boolean` fields to the `ArtifactEntity` data class.
- This ensures compile-time type safety for the status field.

#### [MODIFY] [Converters.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/Converters.kt)
- Add `@TypeConverter` methods for `ArtifactStatus` to handle conversion between the enum and its `String` representation for Room storage.

#### [MODIFY] [AppDatabase.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/AppDatabase.kt)
- Increment the database version from `57` to `58`.
- Note: `DatabaseModule.kt` already includes `fallbackToDestructiveMigration(true)`, so the schema change will be handled automatically by wiping the local cache.

### Repository & Paging

#### [MODIFY] [ArtifactRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt)
- Update `mapArtifactToEntity()` to persist `status` and `isDraft` from the `Artifact` model.
- Update `mapArtifactEntityToArtifact()` to restore `status` and `isDraft` from the `ArtifactEntity`.
- **Remove** any silent defaulting to `ArtifactStatus.DRAFT` in mappers.
- Update `finalizeArtifactDocument()` to atomically write the following fields to Firestore:
    - `status = ArtifactStatus.ACTIVE.name`
    - `isDraft = false`
    - `audioUrl = audioUrl`
    - `isPublic = isPublic`
    - `visibility = if (isPublic) Visibility.PUBLIC.name else Visibility.PRIVATE.name`

#### [MODIFY] [ArtifactRemoteMediator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/paging/ArtifactRemoteMediator.kt)
- Update `mapToEntity()` to ensure `status` and `isDraft` are preserved when caching Firestore documents into `ArtifactEntity`.

### Testing

#### [NEW] [ArtifactStatusRoundTripTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/repository/ArtifactStatusRoundTripTest.kt)
- Add a regression test specifically for the `status`/`isDraft` round-trip: `Firestore -> Artifact -> ArtifactEntity -> Artifact`.
- Assert that `status == ACTIVE` and `isDraft == false` after the full cycle.

## Verification Plan

### Automated Tests
- Run `ArtifactStatusRoundTripTest`.
- Run existing `ArtifactRepositoryTest` and `ArtifactRemoteMediatorTest`.

### Manual Verification
1. **Phase 1 — Firestore**: Publish an artifact and verify in Firebase Console that `status = "ACTIVE"` and `isDraft = false`.
2. **Phase 2 — Room**: Use logs or database inspection to confirm `ArtifactEntity.status == ACTIVE` and `isDraft == false`.
3. **Phase 3 — Repository**: Verify `Artifact.status == ACTIVE` after reading from local cache.
4. **Phase 4 — UI**:
    - Confirm "Share Artifact" appears (requires `ACTIVE` status).
    - Confirm no "Draft" badge is visible.
    - Confirm correct display in both Feed and Profile.
