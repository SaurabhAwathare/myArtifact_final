# Architectural Optimization Strategy for `myArtifact`

This plan outlines the technical strategy to eliminate startup jank, optimize recording responsiveness, and implement a progressive hydration architecture aligned with the app's "emotionally ambient" philosophy.

## User Review Required

> [!IMPORTANT]
> - **Media3 Initialization**: We are moving from "Eager-ish" to "Strictly Lazy". This means the first play event will have a slightly higher latency (~100-200ms) but startup will be butter-smooth.
> - **Recording UX**: Recording will now show an "Initializing..." state for ~300ms to allow off-main-thread `MediaRecorder` setup.
> - **Hydration**: Feed items will hydrate progressively. Off-screen or fast-scrolled items will not trigger heavy logic.

## Proposed Changes

### 1. Startup Orchestration & Budgeting
Refactor `FeedViewModel.start()` to use a structured, priority-based orchestration instead of simple delays.

#### [FeedViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/FeedViewModel.kt)
- Introduce `StartupPhase` enum.
- Replace `start()` logic with a `MutableStateFlow<StartupPhase>` driven sequence.
- Offload `PersonalizationEngine` and `AdManager` initialization to `Dispatchers.Default`.

---

### 2. Progressive Hydration Architecture
Optimize `FeedScreen` to prevent "Observer Storms" during scroll and startup.

#### [FeedScreen.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/FeedScreen.kt)
- Debounce hydration requests using `snapshotFlow` + `debounce`.
- Only hydrate when `listState.isScrollInProgress` is false.
- Use `derivedStateOf` to limit recomposition triggers for visible item tracking.

---

### 3. Media3 Deferred Initialization
Ensure `AudioPlayer` doesn't touch the main thread or allocate heavy resources until necessary.

#### [AudioPlayer.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/AudioPlayer.kt)
- Wrap `MediaController` creation in a `lazy` block or a deferred initialization flow.
- Pre-warm the controller in `StartupPhase.BACKGROUND_STABLE` (Phase 3).

---

### 4. Recording Startup Isolation
Eliminate `MediaRecorder` stalls on the main thread.

#### [RecordingService.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/RecordingService.kt)
- Move `AudioRecorder` instantiation and `MediaRecorder.prepare()` to `serviceScope` with `Dispatchers.IO`.
- Implement a `Preparing` state for the UI to handle the async initialization.

---

### 5. Firestore Stability
Fix the missing index and optimize query observers.

#### [ArtifactRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt)
- Add explicit logging/handling for the `FAILED_PRECONDITION` index error.
- Ensure `getLikedArtifacts` uses a shared flow or cache to prevent redundant startup observers.

## Verification Plan

### Automated Tests
- `gradle_build("app:assembleDebug")` to ensure no regression.
- `Macrobenchmark` (if available) to measure `StartupMetrics`.

### Manual Verification
- **Logcat Monitoring**: Check `APP_FLOW` logs for phased execution.
- **Jank Detection**: Monitor "Skipped frames" in Logcat during startup and recording toggle.
- **UI State Check**: Verify "Initializing" state appears in Recording screen before active recording starts.
