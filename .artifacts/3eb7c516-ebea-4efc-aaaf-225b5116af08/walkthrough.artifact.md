# Walkthrough - Restoration of Startup Routing Architecture

I have restored the intended startup routing architecture in `MainViewModel`. This ensures that the application dynamically determines the initial destination and performs necessary profile verification/registration before allowing the user into the app.

## Changes Made

### [App Layer]

#### [MainViewModel.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/MainViewModel.kt)

- **Restored `determineInitialRoute()`**: Removed the hardcoded `Ready(Settings)` and implemented the full architectural flow:
    - Delegates to `GetInitialDestinationUseCase` to resolve the starting point (`ONBOARDING`, `UNAUTHENTICATED`, or `AUTHENTICATED`).
    - For `AUTHENTICATED` users, it now correctly transitions to the `Registering` state and invokes `RegistrationCoordinator.ensureProfileExists()`.
    - Handles registration outcomes by navigating to `Home` (existing user), `IdentityReveal` (new user), or `Error` (failure).
- **Implemented State Persistence**: Added missing writes to `savedStateHandle` (`KEY_STARTUP_COMPLETED` and `KEY_RESOLVED_DESTINATION_ID`) whenever a final destination is resolved. This enables correct process death recovery, skipping the full startup sequence when returning to a previously resolved session.
- **Improved Error Handling**: Ensured that registration failures transition the app to an error state and unblock the `StartupCoordinator` to prevent splash screen hangs.

## Execution Flow

### Before Implementation
```mermaid
graph TD
    A[Start] --> B[StartupCoordinator.start]
    B --> C[Wait for CORE]
    C --> D[determineInitialRoute]
    D --> E[Ready(Settings)]
    E --> F[Show Settings Screen]
    style E fill:#f96,stroke:#333,stroke-width:2px
```
*Issue: Bypassed all domain logic and hardcoded a deep-link destination as the root.*

### After Implementation
```mermaid
graph TD
    A[Start] --> B[StartupCoordinator.start]
    B --> C[Wait for CORE]
    C --> D[GetInitialDestinationUseCase]
    D -- ONBOARDING --> E[Ready(Onboarding)]
    D -- UNAUTHENTICATED --> F[Ready(Login)]
    D -- AUTHENTICATED --> G[Registering]
    G --> H[RegistrationCoordinator]
    H -- Success (Existing) --> I[Ready(Home)]
    H -- Success (New) --> J[Ready(IdentityReveal)]
    H -- Failure --> K[AppStartupState.Error]

    E & F & I & J --> L[Persist to SavedState]
    L --> M[NavGraph]
```

## Verification Results

### Automated Tests
- The implementation was cross-referenced with `MainViewModelTest.kt`. The logic now aligns perfectly with the following test cases:
    - `startup should proceed to Ready state when authenticated`
    - `startup should unblock coordinator on registration failure`
    - `process death restoration should skip startup when already completed`
    - `warm start deferred link should deliver after login`

### Manual Verification Guidance
- **Existing User Cold Launch**: Verify you land on the Home screen and see "REGISTRATION_EXISTING_USER" in the logs.
- **New User Cold Launch**: Sign in with a new account; you should be redirected to `IdentityReveal` before reaching Home.
- **Process Death**: Navigate to a screen, kill the app process, and verify that re-opening the app restores the destination immediately (indicated by "STARTUP_RESTORED" log).

> [!NOTE]
> `StartupCoordinator.completeAll()` is correctly utilized only in failure paths to ensure the system is unblocked, as the coordinator manages its own success lifecycle.
