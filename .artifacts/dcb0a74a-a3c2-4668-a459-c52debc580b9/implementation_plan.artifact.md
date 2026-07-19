# Implementation Plan: Deduplicate Profile Drafts

This plan addresses the `LazyColumn` key collision by ensuring that only one representation of a draft is shown on the Profile screen. If a local draft exists, it will be prioritized, and any matching cloud artifact will be suppressed.

## User Review Required

> [!IMPORTANT]
> The deduplication logic will be implemented in the domain layer (`GetProfileDataUseCase`). This ensures that the UI always receives a clean, collision-free data set, regardless of how the artifacts are rendered.

## Proposed Changes

### [Domain Layer]

#### [MODIFY] [GetProfileDataUseCase.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/profile/GetProfileDataUseCase.kt)
- Update the `combine` logic to extract IDs from `localDrafts`.
- Filter `allArtifacts` to ensure that `cloudDrafts` do not contain any IDs already present in `localDrafts`.

### [Testing]

#### [NEW] [GetProfileDataUseCaseTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/domain/profile/GetProfileDataUseCaseTest.kt)
- Add unit tests to verify the deduplication logic.
- Scenario: Artifact ID `A` exists in both `localDrafts` (Room) and `allArtifacts` (Firestore with status `PENDING_UPLOAD`).
- Expected: `ProfileData.localDrafts` contains `A`, but `ProfileData.cloudDrafts` does NOT contain `A`.

## Verification Plan

### Automated Tests
- Run the new `GetProfileDataUseCaseTest.kt`.
- Run existing `ProfileViewModelTest.kt` (if it exists) to ensure no regressions in the UI state mapping.

### Manual Verification
- Deploy the app and initiate a publication.
- Navigate to the Profile -> Drafts tab during the upload.
- Verify that no crash occurs and only the local draft representation (with progress bar) is visible.
