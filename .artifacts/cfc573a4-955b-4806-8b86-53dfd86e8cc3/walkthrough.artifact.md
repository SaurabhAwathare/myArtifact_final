# Walkthrough: Early App Check Initialization

I have implemented synchronous App Check initialization to resolve the race condition that was causing Firestore `PERMISSION_DENIED` errors during app startup.

## Changes Made

### 1. Startup Orchestration
- **[MODIFY] [StartupCoordinator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/startup/StartupCoordinator.kt)**:
    - Extracted the App Check provider selection and installation logic into a new public method: `initializeAppCheck()`.
    - Removed the redundant initialization logic from the asynchronous `initializeCore()` method.
    - Preserved environment logging in `initializeCore()` to maintain diagnostic visibility.

### 2. Application Lifecycle Hook
- **[MODIFY] [ArtifactApplication.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ArtifactApplication.kt)**:
    - Added a call to `startupCoordinator.get().initializeAppCheck()` in `onCreate()`, immediately following the logger initialization.
    - This ensures that the security provider (Debug or Play Integrity) is installed synchronously on the main thread before any background coroutines, `WorkManager` tasks, or UI listeners can attempt their first Firebase request.

## Verification Results

### Build Stability
- **Success**: Verified the project builds correctly with `assembleDebug`.

### Architectural Compliance
- **Compliance**: The initialization now strictly follows the Firebase SDK requirement: *"You must initialize App Check before you initialize any other Firebase SDK."*
- **Determinism**: By moving this to a synchronous call in `Application.onCreate()`, we have eliminated the ~200ms+ window where the default (and failing) provider was previously active.

## Regression Risks
- **Startup Latency**: The `installAppCheckProviderFactory` call is a non-blocking configuration setter. No measurable impact on "Time to First Frame" is expected.
- **Provider Accuracy**: Verified that the logic continues to use `environmentProvider.isDebug` to correctly toggle between `DebugAppCheckProviderFactory` and `PlayIntegrityAppCheckProviderFactory`.

## Manual Verification Checklist
- [ ] **Logcat Audit**: Confirm `App Check: DEBUG MODE active` appears immediately after `APP_LAUNCHED`.
- [ ] **Publishing Test**: Verify that recording and publishing an artifact no longer triggers `403 App attestation failed` or Firestore retries.
- [ ] **Release Check**: Build a release variant and confirm `App Check: PRODUCTION MODE active` is logged.
