# Implementation Plan - Enable Resilient Draft Deletion from REVIEW_REQUIRED

Allow durable local drafts to transition from REVIEW_REQUIRED to DELETING, restoring the intended resilient deletion architecture while preserving the durability barrier.

## Proposed Changes

### Lifecycle Transition Update
Update `ArtifactLifecycle.kt` to allow `REVIEW_REQUIRED` to transition to `DELETING`.

#### [MODIFY] [ArtifactLifecycle.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/model/ArtifactLifecycle.kt)

### Backup Worker Protection
Ensure `BackupSyncWorker` ignores drafts in `DELETING` or `DELETED` states.

#### [MODIFY] [BackupSyncWorker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/BackupSyncWorker.kt)

### ReviewSessionManager Validation
Remove enum ordinal dependency in `ReviewSessionManager.kt`.

#### [MODIFY] [ReviewSessionManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/ReviewSessionManager.kt)

### Publishing Studio Handling
Handle `DELETING` state in the Publishing Studio (e.g., `StudioStep.fromLifecycle()`).

### Recovery Validation
Ensure `RecoveryWorker.kt` detects `DELETING` state and resumes deletion.

#### [MODIFY] [RecoveryWorker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/RecoveryWorker.kt)

## Verification Plan

### Automated Tests
- `ArtifactLifecycleTest`: Verify `REVIEW_REQUIRED` -> `DELETING` transition.
- `RecoveryWorkerTest`: Verify detection and resumption of `DELETING` drafts.
- `BackupSyncWorkerTest`: Verify skipping of `DELETING` drafts.

### Manual Verification
1. Record -> Review -> Delete: Verify immediate disappearance and file deletion.
2. Crash Simulation: Force stop during deletion and verify `RecoveryWorker` resumes.
3. Backup: Ensure no backup starts for a draft being deleted.
4. Publishing Studio: Delete draft while Studio is open and verify graceful closure.
