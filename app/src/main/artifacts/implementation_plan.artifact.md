# Feed Hydration Stability and Performance Implementation Plan

This plan addresses the user's feedback regarding feed hydration stability, removal of artificial delays, and adding startup metrics.

## Proposed Changes

### [Feed & Hydration]

#### [FeedViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/FeedViewModel.kt)
- Introduce a private `CoroutineScope` with `SupervisorJob()` to isolate hydration tasks.
- Update `start()` to trigger `refreshReflectionPrompt()` and `loadRankedFeed()` immediately (on `STABLE` stage) and in parallel using isolated `launch` blocks.
- Wrap Firestore calls in `runCatching` to ensure the UI state (e.g., loading flags) is always updated, preventing infinite loading screens.
- Implement isolated listener launches for different data streams.

#### [FeedScreen.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/FeedScreen.kt)
- Remove `contentVisible` delay (set to true immediately).
- Remove `viewModel.start()` delay in `LaunchedEffect(Unit)`.
- Remove staggered item entrance delay in `ArtifactItem`.
- Ensure `EmptyFeedState` and `FeedLoadingState` are correctly rendered based on ViewModel state.

---

### [Startup & Performance]

#### [NEW] [StartupMetrics.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/startup/StartupMetrics.kt)
- Create a singleton object to track:
    - `coldStartDuration` (Application.onCreate to first Feed render)
    - `authRestoreDuration` (Start to Auth ready)
    - `feedHydrationDuration` (Feed start to first artifact rendered)

#### [ArtifactApplication.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ArtifactApplication.kt)
- Call `StartupMetrics.onAppCreate()` in `onCreate()`.

#### [MainViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/MainViewModel.kt)
- Record `authRestoreDuration` when `determineInitialRoute()` completes.

---

### [Cleanup]

#### [Global Search]
- Remove any remaining unnecessary `delay()` calls related to startup or artificial pacing.

## Verification Plan

### Automated Tests
- `gradle_build("app:assembleDebug")` to ensure compilation.

### Manual Verification
- Deploy the app and monitor Logcat for the new `StartupMetrics`.
- Verify the feed loads immediately without artificial delays.
- Test "degraded" states by simulating Firestore failures (if possible) to ensure the UI remains responsive and doesn't get stuck in a loading state.
