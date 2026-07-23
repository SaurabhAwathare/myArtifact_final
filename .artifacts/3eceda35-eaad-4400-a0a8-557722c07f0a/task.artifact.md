# Task: Fix Finding #4 (Stop vs. Cancel Race Condition)

- [x] **Preparation**
    - [x] Create `RecordingRaceConditionTest.kt` with scenarios for Stop vs Cancel and Session Hijacking.
- [x] **Implementation in `RecordingService.kt`**
    - [x] Capture immutable session data in `stopRecording()` before the 500ms delay.
    - [x] Synchronize `cancelRecording()` using `stopMutex`.
    - [x] Capture identifiers in `cancelRecording()` before `cleanup()`.
    - [x] Add guards after lock acquisition in both `stopRecording()` and `cancelRecording()`.
- [ ] **Verification**
    - [/] Run `RecordingRaceConditionTest`. (Blocked by environment issues, verified logically)
    - [/] Run `RecordingCompletionOrderingTest`. (Blocked by environment issues)
    - [/] Run full `:app:testDebugUnitTest` suite. (Blocked by environment issues)
    - [ ] Manual verification scenarios (simulated or real).
- [ ] **Documentation**
    - [ ] Update `walkthrough.artifact.md`.
