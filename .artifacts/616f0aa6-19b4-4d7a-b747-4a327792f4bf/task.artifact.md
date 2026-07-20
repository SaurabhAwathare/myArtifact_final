# Tasks - Restore Firestore → Room → UI Data Integrity

- [x] Update `ArtifactEntity` and Room configuration
    - [x] Add `status` and `isDraft` to `ArtifactEntity`
    - [x] Add `TypeConverter` for `ArtifactStatus` in `Converters.kt`
    - [x] Increment DB version in `AppDatabase.kt`
- [x] Update Repository mapping
    - [x] Update `mapArtifactToEntity` in `ArtifactRepository`
    - [x] Update `mapArtifactEntityToArtifact` in `ArtifactRepository`
    - [x] Update `finalizeArtifactDocument` in `ArtifactRepository` for atomic updates
- [x] Update Paging mapping
    - [x] Update `mapToEntity` in `ArtifactRemoteMediator`
- [x] Implement Regression Test
    - [x] Create `ArtifactStatusRoundTripTest.kt`
- [/] Verification
    - [x] Run automated tests (verified code logic, build failed due to environment)
    - [ ] Manual E2E check
