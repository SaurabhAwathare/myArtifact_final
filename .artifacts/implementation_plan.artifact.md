# Implementation Plan - Final Polish & Fixes

Address the remaining blockers identified in the ship readiness audit to ensure a stable and reliable production build.

## User Review Required

> [!IMPORTANT]
> **Restoring Hybrid Orchestration**: I plan to re-enable the `UploadService` in the main publishing flow. This will ensure that users see a foreground notification with progress, which was previously bypassed.

## Proposed Changes

### 1. Repository & Infrastructure

#### [MODIFY] [PublishingManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/PublishingManager.kt)
- Add `@OptIn(UnstableApi::class)` to the class or the `cleanupManager` property to resolve the compiler error.

### 2. Orchestration

#### [MODIFY] [PublishingOrchestrator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/PublishingOrchestrator.kt)
- Update `approvePublishing` to call `UploadService.start(context, draftId)` before enqueuing WorkManager.
- Remove the redundant/unused `approveAndPublish` method.
- Remove redundant check `if (draft.lifecycle == ArtifactLifecycle.PUBLISHED)` that is logically unreachable.

### 3. Testing Suite

#### [MODIFY] [RecordingFinalizationIdempotencyTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/repository/RecordingFinalizationIdempotencyTest.kt)
- Update the `RecordingRepository` constructor call to remove `deletionManager`, matching the new class signature.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:testDebugUnitTest` to ensure all unit tests (including the fixed one) pass.
- Run `./gradlew :app:compileDebugKotlin` to verify all opt-in issues are resolved.

### Manual Verification
- Deploy to emulator and initiate a publish.
- Verify that the `UploadService` foreground notification appears immediately.
- Verify that publication completes successfully.
