# App Startup Performance Optimization

This plan focuses on reducing the Time to Initial Display (TTID) and improving the overall perceived startup speed by optimizing component injection, tuning intentional delays, and formalizing the initialization sequence.

## Proposed Changes

### Core Initialization

#### [ArtifactApplication.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ArtifactApplication.kt)
- Move non-critical initialization logic to background threads or deferred launchers.
- Use `Lazy` injection for `MemoryManager` and `StartupCoordinator`.

#### [DependencyGraphInitializer.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/startup/DependencyGraphInitializer.kt)
- Move SQLCipher and Firebase checks from `Application.onCreate` to this initializer.
- Add "warm-up" calls for critical DataStore flows (like `OnboardingManager`).

---

### UI & Lifecycle

#### [MainActivity.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/MainActivity.kt)
- Use `dagger.Lazy` for all injected managers that are not required for the initial Compose frame.
- This prevents blocking the main thread during `Activity.onCreate`.

---

### Startup Sequence

#### [StartupCoordinator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/startup/StartupCoordinator.kt)
- Reduce conservative intentional delays to make the "Island Architecture" feel snappier.
- Tune stagger durations based on expected load times.

---

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to ensure compilation.
- Monitor Logcat for `STARTUP_METRICS` logs to compare "Auth Ready" time before and after changes.

### Manual Verification
- Observe the splash screen duration on a cold start.
- Ensure the app transitions smoothly from the splash screen to the home/login screen.
- Verify that features like recording and onboarding still work correctly after lazy injection.
