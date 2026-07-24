# Restoration of Startup Routing Architecture

Restore the intended startup routing logic in `MainViewModel` to use `GetInitialDestinationUseCase` and `RegistrationCoordinator`, ensuring dynamic destination determination and proper profile verification.

## User Review Required

> [!IMPORTANT]
> This change strictly follows the existing architecture verified in unit tests. It removes the hardcoded `Ready(Settings)` state which was bypassing security and registration checks.

## Verification Results

### 1. `startupCoordinator.completeAll()` Ownership
- **Finding**: `StartupCoordinator` uses `completeAll()` in its own `catch` block. `MainViewModel` also uses it in its `executeStartup` `catch` block as a safety mechanism to unblock the splash screen on terminal failure.
- **Decision**: Maintain the existing usage in the `catch` block. No additional calls are needed on the success path as the coordinator manages its own lifecycle.

### 2. `savedStateHandle` Persistence
- **Finding**: `MainViewModel` currently reads but *never writes* the startup state keys. Tests expect these writes to exist for process death recovery.
- **Decision**: Implement the missing writes to `savedStateHandle` (`KEY_STARTUP_COMPLETED` and `KEY_RESOLVED_DESTINATION_ID`) once a final destination is resolved.

### 3. Registration Failure Policy
- **Finding**: Existing tests and the `RegistrationCoordinator` architecture use `AppStartupState.Error` for terminal failures.
- **Decision**: Adhere to this policy. Failures in `RegistrationCoordinator` will transition the app to `AppStartupState.Error`.

## Proposed Changes

### [App Layer]

#### [MODIFY] [MainViewModel.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/MainViewModel.kt)

- Update `determineInitialRoute()` to delegate to `GetInitialDestinationUseCase`.
- Implement `AUTHENTICATED` flow involving `RegistrationCoordinator`.
- Handle `ONBOARDING` and `UNAUTHENTICATED` flows.
- **Fix**: Update `savedStateHandle` with `KEY_STARTUP_COMPLETED = true` and the resolved `destinationId` upon reaching a `Ready` state.
- Ensure `startupCoordinator.completeAll()` remains in the `catch` block to prevent splash screen hangs.

## Implementation Details

### `determineInitialRoute` Logic:

1.  **Get Initial Destination**: Call `getInitialDestinationUseCase()`.
2.  **Handle Branches**:
    -   **`ONBOARDING`**: Transition to `Ready(Onboarding)`.
    -   **`UNAUTHENTICATED`**: Transition to `Ready(Login)`.
    -   **`AUTHENTICATED`**:
        -   Transition to `AppStartupState.Registering`.
        -   Call `registrationCoordinator.ensureProfileExists()`.
        -   **On `SuccessExistingUser`**: Transition to `Ready(Home)`.
        -   **On `SuccessNewUser`**: Transition to `Ready(IdentityReveal)`.
        -   **On `Failure`**: Transition to `AppStartupState.Error`.
3.  **Persist Destination**:
    -   Whenever `Ready(destination)` is set:
        -   `savedStateHandle[KEY_STARTUP_COMPLETED] = true`
        -   `savedStateHandle[KEY_RESOLVED_DESTINATION_ID] = mapRouteToId(destination)`

## Verification Plan

### Automated Tests
- Run `MainViewModelTest.kt` to ensure all startup scenarios pass.

### Manual Verification
- **Cold launch (No User)**: Should land on Onboarding (if first time) or Login.
- **Cold launch (Existing User)**: Should land on Home.
- **Cold launch (New User)**: Should land on IdentityReveal.
- **Process Death**:
    1. Open app to Home.
    2. Simulate process death (Kill via Logcat or Background).
    3. Re-open app.
    4. Verify it returns to Home immediately without repeating registration checks (using restored state).
- **Deep Link**:
    1. Buffer a deep link (e.g. while at Login).
    2. Log in.
    3. Verify deep link is delivered *after* registration completes and destination is Home.
