# Implementation Plan - RecordingRaceConditionTest Stabilization

Stabilize the `RecordingRaceConditionTest` by addressing missing mocks, Android framework dependencies, and coroutine synchronization issues identified during the static investigation.

## User Review Required

> [!IMPORTANT]
> This plan modifies only the test code. No production changes are required to fix these specific test failures.

## Proposed Changes

### [audio]

#### [MODIFY] [RecordingRaceConditionTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/audio/RecordingRaceConditionTest.kt)

- **Mock Missing Dependencies**: Add `ArtifactCleanupManager` mock and inject it into the `RecordingService`.
- **Bypass Framework Dependencies**:
    - Add `mockkStatic(Log::class)` to handle production logging calls.
    - Add `mockkStatic(Looper::class)` to handle potential lazy MainLooper access.
- **Synchronize Dispatchers**:
    - Add `mockkStatic(Dispatchers::class)`.
    - Redirect `Dispatchers.IO` to the `testDispatcher`. This ensures that blocks like `withContext(Dispatchers.IO)` in `RecordingService` are executed within the virtual time control of the test.
- **Lifecycle Management**: Ensure `unmockkAll()` is called in `@After` to prevent side effects on other tests.

## Verification Plan

### Automated Tests
- Run specific tests:
  `./gradlew :app:testDebugUnitTest --tests "com.saurabh.artifact.audio.RecordingRaceConditionTest"`
- Verify 2/2 pass.
- Run the full unit test suite (319 tests) to ensure no regressions.

### Criteria for Success
- [ ] No `NoSuchFieldException` (already fixed).
- [ ] No `UninitializedPropertyAccessException` for `cleanupManager`.
- [ ] No "Method ... in android.util.Log not mocked" errors.
- [ ] No "Looper not mocked" errors.
- [ ] `session hijacking` test reaches `COMPLETED` status.
- [ ] 319/319 tests pass in the full suite.
