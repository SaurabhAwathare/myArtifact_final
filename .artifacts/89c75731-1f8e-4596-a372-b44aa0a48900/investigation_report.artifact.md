# Investigation Report — Incorrect Startup Destination (`Settings` vs `Home`)

## Problem Statement
Artifact launches into the **Settings** screen instead of the expected **Home** screen immediately after startup.

## Question Being Answered
Why does `MainViewModel` emit `AppStartupState.Ready(Settings)` instead of `AppStartupState.Ready(Home)` during application startup?

## Evidence Collected

### Code Evidence (Level 2)
1.  **Hardcoded Destination**: [MainViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/MainViewModel.kt#L228) contains the following implementation:
    ```kotlin
    private suspend fun determineInitialRoute() {
        _startupState.value = AppStartupState.Ready(Settings)
    }
    ```
2.  **Ignored Components**: The following components are injected into `MainViewModel` but are **unused**:
    - `GetInitialDestinationUseCase` (intended to choose between Onboarding, Home, and Login)
    - `RegistrationCoordinator` (intended to ensure profile health before entering Home)
3.  **Test Expectation Mismatch**: [MainViewModelTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/MainViewModelTest.kt) explicitly verifies that successful startup leads to `Ready(Home)` or `Ready(Login)`, never `Ready(Settings)`.
4.  **Process Death Incompatibility**: The current implementation of `mapIdToRoute` and `mapRouteToId` in `MainViewModel.kt` does not support `Settings`, meaning navigation state restoration will fail if the app is killed while on the (incorrectly) reached Settings screen.

## Execution Trace (Cold Launch)
```
Cold Launch
    ↓
MainActivity.start()
    ↓
MainViewModel.start()
    ↓
MainViewModel.executeStartup()
    ↓
StartupCoordinator.start()
    ↓
(Wait for StartupComponent.CORE readiness)
    ↓
MainViewModel.determineInitialRoute()
    ↓
_startupState.emit(Ready(Settings)) // Hardcoded Regression
```

## Findings
- **Origin of Issue**: The `determineInitialRoute` method is a skeleton implementation that forces the application to the `Settings` screen.
- **Intent**: No code markers (`TODO`, `TEMP`, `DEBUG`) were found to explain this behavior.
- **Regression Profile**: This appears to be a regression or an accidental commit of debug code, as it contradicts both the unit tests and the logical presence of the `GetInitialDestinationUseCase`.

## Root Cause
The root cause is a **hardcoded assignment** in `MainViewModel.determineInitialRoute` which bypasses the destination selection logic and the profile verification flow.

## Confidence Level
**Level 2 — Code Evidence**: The cause is explicitly visible in the source code and contradicts the established test suite.

## Remaining Unknowns
- The investigation into why the **Home screen is black** remains deferred, but it is highly likely that bypassing the `RegistrationCoordinator` and `AUTH` readiness signals leaves the application in an uninitialized state that contributes to rendering failures.
