# Refactor and Modernize WorkManager Usage

The project had significant architectural debt in its `WorkManager` implementation, including redundant workers, lack of dependency injection, and dead code. This plan outlines the consolidation and modernization of background tasks.

## Proposed Changes

### Core Infrastructure

#### [NEW] [WorkModule.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/di/WorkModule.kt)
- Provides `WorkManager` instance via Hilt for dependency injection.

### Worker Consolidation

#### [PublishingWorker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/PublishingWorker.kt)
- Added `UploadGuard` validation to ensure only authorized uploads proceed.
- Added checksum integrity checks.
- Integrated `ConnectivityObserver` logic.
- Standardized on `DraftRepository` for all state updates.

#### [NEW] [PublishingRecoveryWorker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/PublishingRecoveryWorker.kt)
- Periodically scans for stuck or queued publications in `UploadTaskDao` and re-triggers them.

#### [DELETE] [DraftSyncWorker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/DraftSyncWorker.kt)
- Removed obsolete sync worker.

#### [DELETE] [SyncRecoveryWorker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/SyncRecoveryWorker.kt)
- Removed obsolete recovery worker.

#### [DELETE] [UploadWorker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/UploadWorker.kt)
- Removed obsolete upload worker.

### Dependency Injection Refactoring

#### [RecordingRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/RecordingRepository.kt)
- Switched from `WorkManager.getInstance(context)` to injected `WorkManager`.

#### [PublishingOrchestrator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/PublishingOrchestrator.kt)
- Switched from `WorkManager.getInstance(context)` to injected `WorkManager`.
- Enabled `setExpedited(true)` for publishing work.

#### [DraftDeletionManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/DraftDeletionManager.kt)
- Switched from `WorkManager.getInstance(context)` to injected `WorkManager`.

#### [ArtifactCleanupManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/ArtifactCleanupManager.kt)
- Switched from `WorkManager.getInstance(context)` to injected `WorkManager`.

#### [StartupCoordinator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/startup/StartupCoordinator.kt)
- Switched from `WorkManager.getInstance(context)` to injected `WorkManager`.
- Scheduled the new `PublishingRecoveryWorker`.

## Verification Plan

### Automated Tests
- Run `app:assembleDebug` to ensure all Dagger/Hilt dependencies are correctly wired.
- Verify `WorkManager` configuration in `ArtifactApplication`.

### Manual Verification
- Trigger a publication and verify it uses the `PublishingWorker`.
- Simulate a network failure during upload and verify `PublishingRecoveryWorker` re-triggers it after 1 hour (or manually trigger for testing).
- Verify that `DraftSyncWorker` and `UploadWorker` are no longer referenced in the codebase.
