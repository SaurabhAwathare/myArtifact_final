# Implementation Plan - Runtime Verification of StartupCoordinator.awaitComponent()

Verify if `startupCoordinator.awaitComponent(CORE)` is blocking `determineInitialRoute()` in `MainViewModel` tests.

## User Review Required

> [!IMPORTANT]
> This plan involves a temporary modification to `MainViewModelTest.kt` to stub a suspend function that might be causing a deadlock or preventing test progression.

## Proposed Changes

### [Component Name]

#### [MODIFY] [MainViewModelTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/MainViewModelTest.kt)

Add the following stub to the `setup()` method:
```kotlin
coEvery {
    startupCoordinator.awaitComponent(com.saurabh.artifact.startup.StartupComponent.CORE)
} returns Unit
```

## Verification Plan

### Automated Tests
- Run `MainViewModelTest` and verify progression evidence.
- Command: `./gradlew :app:testDebugUnitTest --tests com.saurabh.artifact.MainViewModelTest`

### Evidence Collection (Test Level)
- [ ] **Invocation Check**: `coVerify { startupCoordinator.awaitComponent(CORE) }`
- [ ] **Flow Progression**: Verify `getInitialDestinationUseCase()` is called exactly once.
- [ ] **Final State**: Assert `viewModel.startupState.value` is `AppStartupState.Ready(...)`.
- [ ] **Scope Check**: Ensure no other `awaitComponent` calls are blocking (e.g. `AUTH`, `DATABASE`).

### Manual Verification
- Observe the test logs and report the exact execution trace.
