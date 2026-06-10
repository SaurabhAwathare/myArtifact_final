# App Startup Performance Optimization Walkthrough

I have optimized the app's startup sequence to reduce the "Time to User Action" and make the transition from the splash screen to the main UI feel much snappier.

## Key Changes

### 1. Lazy Injection in MainActivity
[MainActivity.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/MainActivity.kt) now uses `dagger.Lazy` for heavy managers like `RecordingSessionManager` and `OnboardingManager`.
- **Benefit**: This prevents these complex objects from being constructed during `Activity.onCreate`, which keeps the main thread free to start the Compose rendering engine immediately.

### 2. Formalized Initialization Sequence
Core system setup has been moved to [DependencyGraphInitializer.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/startup/DependencyGraphInitializer.kt).
- **Benefit**: Uses the official Android Startup library to handle SQLCipher loading and Firebase checks before the `Application` class even starts its own `onCreate`. This organizes the "pre-app" phase more efficiently.

### 3. Snappier Startup Staggers
The intentional delays in [StartupCoordinator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/startup/StartupCoordinator.kt) have been tuned.
- **Benefit**: Reduced the total intentional delay from ~3.5s to ~1.7s. The app still feels progressive and smooth, but users reach the "Stable" state twice as fast.

| Stage Transition | Previous Delay | New Delay |
| :--- | :--- | :--- |
| Arrival -> Presence | 500ms | 200ms |
| Presence -> Discovery | 500ms | 200ms |
| Discovery -> Immersion | 500ms | 300ms |
| Immersion -> Ritual | 1000ms | 500ms |
| Ritual -> Stable | 1000ms | 500ms |

## Verification Results

### Build Success
The project compiles correctly with the new lazy injection patterns.
`./gradlew :app:assembleDebug` passed successfully.

### Performance Logs
You can verify the improvements by looking for `STARTUP_METRICS` and `StartupTrace` in Logcat:
- `StartupTrace: Transition: PRESENCE` should now appear much closer to the process start time.
- `STARTUP_METRICS: Auth Ready` should show a decrease in total duration.
