# Implementation Plan: Safe WorkManager Cancellation & Work Name Centralization

This plan addresses the proactive cancellation of the fallback `PublishingWorker` after a successful `UploadService` publish. It also includes refactoring to centralize the generation of `WorkManager` unique work names to prevent future regressions.

## Proposed Changes

### [Core] [PublishingWorker](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/worker/PublishingWorker.kt)
- [MODIFY] Add `getUniqueWorkName(draftId: String)` to the companion object.
- [MODIFY] Centralize the `"publish_"` prefix.

### [Domain] [PublishingOrchestrator](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/domain/PublishingOrchestrator.kt)
- [MODIFY] Add `getUniqueWorkName(draftId: String)` for processing work to the companion object.
- [MODIFY] Centralize the `"process_"` prefix.
- [MODIFY] Update `startProcessing`, `isProcessingActive`, and `enqueuePublishingWork` to use the new helpers.

### [Service] [UploadService](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/audio/UploadService.kt)
- [MODIFY] Import `androidx.work.WorkManager`.
- [MODIFY] In `startUpload`, call `WorkManager.getInstance(applicationContext).cancelUniqueWork(...)` inside the `onSuccess` block of `performPublish`.

### [Maintenance] Cleanup String Literals
- [MODIFY] [PublishingRecoveryWorker.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/worker/PublishingRecoveryWorker.kt): Use `PublishingWorker.getUniqueWorkName`.
- [MODIFY] [UploadSessionManager.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/audio/UploadSessionManager.kt) (class `PublishStateManager`): Use helper functions for both processing and publishing work names.
- [MODIFY] [RecordingRepository.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/repository/RecordingRepository.kt): Use `PublishingOrchestrator.getUniqueWorkName`.

## Verification Plan

### Automated Tests
- Run `gradlew :app:assembleDebug` to verify compilation.
- (Optional) Run `PublishingWorkerRecoveryTest.kt` if applicable.

### Manual Verification
- Verify via Logcat that `UPLOAD_SERVICE_SUCCESS` is followed by successful `WorkManager` cancellation.
- Verify that if the Service is killed manually, the `PublishingWorker` still starts as a fallback.
