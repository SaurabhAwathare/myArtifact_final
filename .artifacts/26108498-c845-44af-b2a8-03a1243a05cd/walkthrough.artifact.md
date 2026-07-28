# Walkthrough - Updating RecordingRaceConditionTest

Updated `RecordingRaceConditionTest.kt` to align with the current production architecture and repository signatures. Resolved a persistent Gradle environment failure blocking verification.

## Changes Made

### Audio Tests

#### [RecordingRaceConditionTest.kt](file:///F:/Android%20Project/01/app/src/test/java/com/saurabh/artifact/audio/RecordingRaceConditionTest.kt)

- **Updated `finalizeRecording` Verification**:
    - Adjusted `coEvery` and `coVerify` to include the 4th parameter (`targetLifecycle`) required by the updated `RecordingRepository.finalizeRecording` signature.
- **Improved Mocking Stability**:
    - Added `Dispatchers.Default` stubbing to the `setup()` block to prevent MockK internal verification errors ("call 1 of 2") when static mocks are active.
    - Used `unmockkStatic(Dispatchers::class)` immediately before verification to ensure the `coVerify` block only captures intended interactions with the repository mock.
- **Updated Cleanup Verification**:
    - Replaced the legacy `draftDao.delete` verification with `cleanupManager.deleteDraft(draftId)`.

### Environment Fixes

- **Resolved `AndroidLocationsBuildService` Failure**:
    - Identified a conflict between `ANDROID_PREFS_ROOT` and `ANDROID_USER_HOME` environment variables.
    - Minimal fix: Unset `ANDROID_PREFS_ROOT` before Gradle execution.

## Verification Results

### Automated Tests
- **Targeted Tests**: `RecordingRaceConditionTest` PASSED both tests.
- **Full Unit Test Suite**: 319/320 tests passed.
- **Remaining Failures**: 1 failure in `DataExportManagerTest` (existing security logic issue, unrelated to these changes).

### Final Confidence Level
- **Level 4**: Full runtime verification successful after resolving environmental and mocking-state conflicts.
