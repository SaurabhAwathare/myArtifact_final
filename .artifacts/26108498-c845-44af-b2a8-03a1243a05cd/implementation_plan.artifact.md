# Resolve Gradle Environment Failure and Fix Test Regressions

Resolve the `AndroidLocationsBuildService` failure blocking verification and fix the MockK regression in `RecordingRaceConditionTest`.

## User Review Required

> [!IMPORTANT]
> The Gradle failure is caused by an environment conflict between `ANDROID_PREFS_ROOT` and `ANDROID_USER_HOME`.
> The test failure is a MockK verification error likely triggered by static mocking of `Dispatchers`.

## Proposed Changes

### Environment Fix
- Unset `ANDROID_PREFS_ROOT` in the shell session before running Gradle. This resolves the `AndroidLocationsException`.

### Audio Test Fix

#### [MODIFY] [RecordingRaceConditionTest.kt](file:///F:/Android%20Project/01/app/src/test/java/com/saurabh/artifact/audio/RecordingRaceConditionTest.kt)
- **Refine `finalizeRecording` Verification**:
    - Add `every { Dispatchers.Default } returns testDispatcher` to `setup()` to ensure all core dispatchers are covered by the static mock.
    - Alternatively, ensure `coVerify` uses explicit matchers for all parameters including the default one.
    - I will try adding `Dispatchers.Default` stubbing first as it's the most likely cause of the "call 1 of 2" confusion in MockK when static mocks are active.

## Verification Plan

### Automated Tests
1. Run targeted tests with environment fix:
   `$env:ANDROID_PREFS_ROOT = $null; ./gradlew :app:testDebugUnitTest --tests "com.saurabh.artifact.audio.RecordingRaceConditionTest"`
2. Run full unit test suite:
   `$env:ANDROID_PREFS_ROOT = $null; ./gradlew :app:testDebugUnitTest`

### Manual Verification
- Confirm that the `AndroidLocationsException` no longer occurs.
- Confirm both tests in `RecordingRaceConditionTest` pass.
