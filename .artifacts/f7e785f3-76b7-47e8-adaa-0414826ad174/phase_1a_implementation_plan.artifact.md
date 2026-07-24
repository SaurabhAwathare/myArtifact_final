# Implementation Plan - Phase 1A: Extract ArtifactLibraryRepository

This plan outlines the extraction of library-related functionality from `ArtifactRepository` into a new `ArtifactLibraryRepository`. This follows the Bridge Pattern to maintain backward compatibility and ensure zero functional regressions.

## Proposed Changes

### [Artifact] (com.saurabh.artifact.repository)

#### [NEW] [ArtifactLibraryRepository](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactLibraryRepository.kt)
- Create a new repository to handle all "Saved Artifacts" (Library) logic.
- **Responsibilities**:
    - `saveArtifact` (Public API)
    - `unsaveArtifact` (Public API)
    - `getSavedArtifactIds` (Public API)
    - `getSavedArtifacts` (Public API)
    - `syncSave` (Internal Sync API for Worker)
    - `syncUnsave` (Internal Sync API for Worker)
- **Dependencies**:
    - `FirebaseFirestore`
    - `PendingInteractionDao`
    - `DiagnosticLogger`
    - `Context` (for `InteractionSyncWorker` enqueuing)

#### [MODIFY] [ArtifactRepository](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt)
- Inject `ArtifactLibraryRepository`.
- Delegate all library methods to `ArtifactLibraryRepository`.
- Add internal comments indicating bridge delegation; do NOT use `@Deprecated` yet.
- Remove `PendingInteractionDao` dependency from `ArtifactRepository`.

#### [MODIFY] [InteractionSyncWorker](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/InteractionSyncWorker.kt)
- Inject `ArtifactLibraryRepository`.
- Update `processInteraction` to call `artifactLibraryRepository.syncSave` and `artifactLibraryRepository.syncUnsave`.
- This ensures the worker remains independent of Firestore-specific naming/implementation details.

## Verification Plan

### Automated Tests
- Run `gradlew :app:assembleDebug` to ensure compilation.
- If unit tests exist for `ArtifactRepository`, run them to ensure no regressions in library logic.

### Manual Verification
1. **Save Artifact**: Verify that saving an artifact still triggers the `InteractionSyncWorker` and eventually persists to Firestore.
2. **Unsave Artifact**: Verify that removing an artifact works similarly.
3. **Library View**: Verify that the "Saved Artifacts" screen correctly reflects the state from Firestore/Cache.
4. **Syncing**: Ensure `InteractionSyncWorker` correctly delegates to the new repository during background sync.
5. **No Duplicates**: Verify that saving the same Artifact multiple times does not create duplicate pending interactions.
6. **Offline Support**: Offline save → reconnect → sync still succeeds.
7. **Rapid Toggling**: Rapid Save/Unsave toggling results in the correct final state (idempotency).
8. **Data Persistence**: Existing saved Artifacts remain visible after upgrading.

## Success Criteria
- [x] `ArtifactLibraryRepository` contains all library-specific logic.
- [x] `ArtifactRepository` delegates without behavior changes.
- [x] No ViewModel changes are required.
- [x] `InteractionSyncWorker` continues functioning.
- [x] Build passes.
- [x] Manual save/unsave verification succeeds.
- [x] Runtime logs show identical behavior before and after extraction.
