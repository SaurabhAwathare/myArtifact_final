# Investigation Plan — Incorrect Startup Destination (`Settings` vs `Home`)

This plan focuses on identifying the root cause of why the application defaults to the `Settings` screen upon successful startup instead of the expected `Home` screen.

## User Review Required

> [!IMPORTANT]
> This is a focused investigation into a specific navigation regression identified in `MainViewModel`.
> Other runtime issues (e.g., black screen on Home) will be addressed in subsequent investigations if they persist after this root cause is resolved.

## Question Being Answered

Why does `MainViewModel` emit `AppStartupState.Ready(Settings)` instead of `AppStartupState.Ready(Home)` during application startup?

## Proposed Investigation Steps

### 1. Analyze `MainViewModel.determineInitialRoute`
- **Goal**: Understand why the initial route is currently hardcoded to `Settings`.
- **Action**:
    - Examine [MainViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/MainViewModel.kt) around lines 242-244.
    - Check for commented-out logic or `TODO`s that might indicate intended behavior (e.g., checking `GetInitialDestinationUseCase`).
    - Verify if any feature flags or `BuildConfig` values are overriding the destination.

### 2. Trace Execution Path in `MainViewModel.start()`
- **Goal**: Confirm that `determineInitialRoute` is the path taken during a normal launch and not bypassed.
- **Action**:
    - Trace the flow from `start()` -> `executeStartup()` -> `determineInitialRoute()`.
    - Provide code evidence that no other state (process death, deep links) is likely overriding this during a standard cold start.

### 3. Compare with `MainViewModelTest` Expectations
- **Goal**: Establish the "contract" for startup navigation.
- **Action**:
    - Read [MainViewModelTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/MainViewModelTest.kt) to see how it verifies the transition to `Home`.
    - Identify the specific test cases that fail (or would fail) given the current implementation.

### 4. Evaluate Intent and Origin
- **Goal**: Determine if this is a regression, a debug leftover, or a partial implementation.
- **Action**:
    - Look for usages of `GetInitialDestinationUseCase` in `MainViewModel` (it is injected but currently unused in `determineInitialRoute`).
    - Search for any comments, `TODO`s, or temporary markers (like "TEMP", "DEBUG", "FIXME") around the navigation logic that explain the hardcoded `Settings` route.

## Verification Plan

### Code Evidence (Level 2)
- Provide a diff/snippet showing the exact location and nature of the hardcoded destination.
- Provide a summary of how `GetInitialDestinationUseCase` *should* have been used based on its implementation and existing tests.
- **Execution Trace**: A concise trace showing the path from `MainActivity.start()` to `Ready(Settings)`.
