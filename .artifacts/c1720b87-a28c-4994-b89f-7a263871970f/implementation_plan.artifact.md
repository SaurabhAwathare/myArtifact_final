# Implementation Plan - Phase 2.2: Eliminate Remaining Legacy Cleanup Paths

This plan eliminates all remaining legacy local deletion paths and direct Room database modifications to ensure that the `ArtifactCleanupManager` -> `CleanupWorker` pipeline is the sole authoritative mechanism for artifact and draft removal.

## User Review Required

> [!IMPORTANT]
> This phase will remove all direct `artifactDao.delete` and `draftDao.delete` calls from Repositories and Services. From this point forward, no component other than the `CleanupWorker` is permitted to remove an artifact record from the local database.

> [!WARNING]
> The `DeletionWorker` will be completely removed. All retry logic is now unified within the `CleanupWorker`.

## Proposed Changes

### 1. Repository Refactoring (Complete Delegation)
Ensure Repositories only delegate to the Cleanup Manager.

#### [MODIFY] [ArtifactRepository.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt)
- Refactor `deletePublishedArtifact`:
  - Keep the remote Firestore deletion logic.
  - Remove ALL direct Room deletions (`artifactDao.deleteById`, `database.engagementDao().deleteEngagement`, `draftDao.deleteById`).
  - **Ownership:** This method will now only ensure the server-side `DELETED` state is set.

#### [MODIFY] [RecordingRepository.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/repository/RecordingRepository.kt)
- Update `recoverInterruptedDrafts()`: Replace `deletionManager.deleteDraft(draft.id)` with `cleanupManager.deleteDraft(draft.id)`.
- Update `purgeZombieDrafts()`: Replace `deletionManager.deleteDraft(draft.id)` with `cleanupManager.deleteDraft(draft.id)`.

### 2. Service Refactoring (Pure Functional Purge)
Strip lifecycle management from the deletion service.

#### [MODIFY] [DraftDeletionManager.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/audio/DraftDeletionManager.kt)
- Refactor `deleteDraft()`:
  - Keep ONLY the physical file removal logic (via `performPhysicalPurge`).
  - Remove `draftDao.markAsDeleting`.
  - Remove `userRepository.decrementArtifactsCount` (this should be handled by the Manager/Repository).
  - Remove `draftDao.deleteById`.
  - Remove `enqueueDeletionRetry` and all WorkManager scheduling.
- **Ownership:** This class is now a "stateless" resource purger used by the `CleanupWorker`.

#### [MODIFY] [RecordingService.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/audio/RecordingService.kt)
- Update `cancelRecording()`: Replace `draftDao.delete(it)` with `cleanupManager.deleteDraft(draftId)`.

### 3. Cleanup Unification
#### [DELETE] [DeletionWorker.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/worker/DeletionWorker.kt)
- Remove the legacy worker.

#### [MODIFY] [ArtifactCleanupManager.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/audio/ArtifactCleanupManager.kt)
- Ensure `deleteArtifact` and `deleteDraft` handle the initial status updates (`PENDING`) and WorkManager scheduling for both published items and drafts.

## Verification Plan

### Static Validation
- Search for `artifactDao.delete` and `draftDao.delete` across the codebase.
- **Expected Result:** The ONLY allowed site for these calls is inside [CleanupWorker.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/worker/CleanupWorker.kt).

### Manual Verification
1.  **Cancel a Recording:** Start recording and tap "Cancel". Verify the draft is cleaned up via the new pipeline.
2.  **Purge Zombies:** Simulate a zombie draft (0 duration, old timestamp). Verify it is cleaned up via `ArtifactCleanupManager` on app startup.
3.  **Delete Published:** Delete a published artifact. Verify physical assets are removed *before* the Room record disappears (via Logcat observation).
