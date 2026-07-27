# Ship Readiness Implementation - Final Ship Blockers

This plan covers the implementation of the three verified fixes identified during the investigation.

## User Review Required

> [!IMPORTANT]
> This phase will modify production and test code to resolve ship-blocking issues.

## Proposed Changes

### 1. Test Suite Restoration
#### [MODIFY] [RecordingFinalizationIdempotencyTest.kt](file:///F:/Android%20Project/01/app/src/test/java/com/saurabh/artifact/repository/RecordingFinalizationIdempotencyTest.kt)
- Align `RecordingRepository` instantiation with the 7-parameter constructor by removing `deletionManager`.

### 2. Publishing UX Restoration
#### [MODIFY] [PublishingOrchestrator.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/domain/PublishingOrchestrator.kt)
- Update `approvePublishing` to call `UploadService.start(context, draftId)` for immediate foreground feedback.

### 3. API Stability Containment
#### [MODIFY] [ArtifactCleanupManager.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/audio/ArtifactCleanupManager.kt)
#### [MODIFY] [LogoutCoordinator.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/domain/auth/LogoutCoordinator.kt)
#### [MODIFY] [MainViewModel.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/MainViewModel.kt)
- Replace `@UnstableApi` propagation with `@OptIn(UnstableApi::class)` to contain Media3 stability requirements within the audio and domain layers.

## Verification Plan

### Automated Tests
- `./gradlew :app:testDebugUnitTest --tests com.saurabh.artifact.repository.RecordingFinalizationIdempotencyTest`
- `./gradlew :app:compileDebugKotlin`

### Manual Verification
- Verify that `UploadService` is triggered when publishing an artifact (observed via logs/state).
