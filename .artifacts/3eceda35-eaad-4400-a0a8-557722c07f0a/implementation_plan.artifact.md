# Implementation Plan - Fix Finding #4 (Stop vs. Cancel Race Condition)

Implement the smallest safe fix that eliminates the verified race condition between `stopRecording()` and `cancelRecording()` in `RecordingService.kt`.

## User Review Required

> [!IMPORTANT]
> The fix involves synchronizing `cancelRecording()` with the same `stopMutex` used by `stopRecording()`. This ensures that if a stop is in progress (including its 500ms delay), a cancel will wait for it to complete or vice versa.

## Proposed Changes

### Audio Component

#### [MODIFY] [RecordingService.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/RecordingService.kt)

- **Synchronize `cancelRecording()`**: Wrap the logic in `serviceScope.launch { stopMutex.withLock { ... } }`.
- **Capture immutable session data in `stopRecording()`**: Before the 500ms `delay()`, capture `draftId` and `outputFile` into local variables.
- **Check `isActive` / `status` after lock acquisition**: Ensure we don't proceed if the recording was already stopped or cancelled.
- **Preserve identifiers in `cancelRecording()`**: Capture `draftId` and `outputFile` before calling `cleanup()`.

### Tests

#### [NEW] [RecordingRaceConditionTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/audio/RecordingRaceConditionTest.kt)

- Create a focused test suite using MockK to simulate `RecordingService` internal state and verify that `stop` and `cancel` interactions behave correctly under concurrent execution.

## Verification Plan

### Automated Tests
- Run `app:testDebugUnitTest` with the new `RecordingRaceConditionTest.kt`.
- Run `RecordingCompletionOrderingTest.kt`.

### Manual Verification
- Deploy the app and perform rapid "Stop" followed by "Cancel" interactions.
- Verify through Logcat that "RECORDING_CANCELLED" and "RECORDING_FINISHED" do not overlap in a way that causes errors or orphaned files.
- Verify that starting a new recording immediately after a cancel works as expected.
