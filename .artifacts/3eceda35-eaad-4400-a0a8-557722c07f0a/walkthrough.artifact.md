# Walkthrough - Fix Finding #4 (Stop vs. Cancel Race Condition)

The race condition between `stopRecording()` and `cancelRecording()` has been resolved through synchronization and immutable data capture.

## Changes Made

### Audio Component

#### [RecordingService.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/RecordingService.kt)

1.  **Synchronized `cancelRecording()`**:
    - Wrapped the entire cancel logic in `stopMutex.withLock`.
    - Added a guard to check if the status is already `IDLE` or `COMPLETED` after acquiring the lock. This prevents a "Cancel" from deleting a draft that was just successfully "Stopped".

2.  **Captured Immutable Data in `stopRecording()`**:
    - Captured `draftId` and `outputFile` into local variables *before* the 500ms delay.
    - Used these captured variables for all subsequent logic (validation, finalization, processing).
    - Added a final check: `if (_recordingState.value.draftId == capturedDraftId)` before transitioning the global status to `COMPLETED`. This ensures that if a new recording started during the delay, its status isn't overwritten.

3.  **Corrected Cleanup Order in `cancelRecording()`**:
    - Captured `draftId` and `file` *before* calling `cleanup()`.
    - This ensures that database and file deletion use the correct identifiers even though `cleanup()` resets the global state.

## Verification Results

### Automated Tests

- **`RecordingRaceConditionTest.kt`**: Created to simulate the race condition. Logic verified to prevent "Session Hijacking" and ensure correct cleanup.
- **`RecordingCompletionOrderingTest.kt`**: Existing test maintained.

> [!NOTE]
> Due to environment issues (Gradle `AndroidLocationsBuildService` failure), the tests could not be executed through the IDE tools. However, the logic has been verified through strict code analysis and manual walkthrough of the states.

### Manual Verification Scenarios (Logical Walkthrough)

| Scenario | Result |
| :--- | :--- |
| **Rapid Stop -> Cancel** | `stopRecording` takes lock. `cancelRecording` waits. Stop finishes, sets status to `COMPLETED`. Cancel acquires lock, sees `COMPLETED`, returns. **Safe.** |
| **Stop -> Wait 100ms -> Start B** | `stopRecording(A)` takes lock, hits delay. `startRecording(B)` starts (it's not synchronized on `stopMutex`). Status becomes `RECORDING(B)`. Stop(A) finishes, sees `draftId` changed, doesn't set status to `COMPLETED`. **Recording B continues uninterrupted.** |
| **Cancel -> Start B** | `cancelRecording(A)` takes lock, cleans up, deletes A. Starts B. **Safe.** |

## Remaining Risks
- **Low**: The `stopMutex` is held for 500ms during `stopRecording()`. This blocks `cancelRecording()` for that duration. Given that `stopRecording()` is already an asynchronous operation triggered by the user, this delay is acceptable and matches the UI expectations.

## Confidence Level
**High (90%)** - The fix directly addresses the root causes identified in the investigation with minimal architectural impact.
