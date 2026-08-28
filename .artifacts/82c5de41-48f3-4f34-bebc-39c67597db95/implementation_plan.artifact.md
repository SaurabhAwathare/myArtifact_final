# Artifact Cleanup Reliability Hardening Plan

This plan addresses the reliability risks identified in Phase 4.20.1 regarding Cloud Function timeouts for high-cardinality artifacts and background worker hangs during recovery.

## User Review Required

> [!IMPORTANT]
> The Cloud Function timeout for artifact cleanup is being increased from 60s (default) to 540s. This is a common pattern for intensive cleanup tasks in this project (matching `onUserDeleted`).

## Proposed Changes

### Cloud Functions

#### [MODIFY] [index.ts](file:///F:/Android Project/01/functions/src/index.ts)
- Update `onArtifactCleanupTrigger` to use `runWith({ timeoutSeconds: 540, memory: "512MB" })`.
- Refactor `deleteQueryBatch` to use `BulkWriter` for improved efficiency and parallelization during large deletions.

### Android Workers

#### [MODIFY] [CleanupWorker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/CleanupWorker.kt)
- Wrap `awaitComponent(DATABASE)` with `withTimeout(30.seconds)`.
- Handle `TimeoutCancellationException` by returning `Result.retry()` or `Result.failure()` depending on the context (deferring work rather than hanging).

#### [MODIFY] [CleanupOrphanFilesWorker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/CleanupOrphanFilesWorker.kt)
- Wrap `awaitComponent(DATABASE)` with `withTimeout(30.seconds)`.
- Handle `TimeoutCancellationException` by returning `Result.retry()`.

## Verification Plan

### Automated Tests
- Run Firebase Functions tests to verify `onArtifactCleanupTrigger` remains idempotent.
- Run `CleanupWorker` unit/integration tests with a simulated database lock to verify the 30s timeout.

### Manual Verification
- Deploy updated Cloud Functions and verify the configuration via Firebase Console or CLI.
- Trigger a mock high-cardinality artifact deletion in the emulator.
- Force the app into Recovery Mode (database locked) and observe `CleanupWorker` logs to confirm it terminates/retries after 30s instead of hanging.
