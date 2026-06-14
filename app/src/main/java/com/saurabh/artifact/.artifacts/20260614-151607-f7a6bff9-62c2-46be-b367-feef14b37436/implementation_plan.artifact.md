# Fix Artifact Review Flow Consistency (Architectural Refactor)

The goal is to fix the bug where tapping "Review Artifact" after recording navigates to the Home Screen without opening the player. This refactor unifies the resolution of playable artifacts (Drafts and Published) and introduces a robust loading state system.

## User Review Required

> [!IMPORTANT]
> **Home Navigation Assumption**: Please confirm that navigating to **Home** behind the expanded player is the desired product behavior.
> *   **Option A (Current Plan)**: Navigate to Home immediately. The player expands *over* the Home Screen. Review happens in the global player.
> *   **Option B**: Stay on the Recording Flow screens until review is finished/closed.
>
> This plan proceeds with **Option A** as it aligns with the "Global Player" pattern.

## Proposed Changes

### 1. Data Layer: Playable Artifact Resolution

Create a unified repository to handle artifact resolution without "failure-driven" flow control.

#### [NEW] [PlayableArtifactRepository.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/repository/PlayableArtifactRepository.kt)

- Implements `resolveArtifact(id: String): Result<Artifact>`.
- Logic:
    1. Check `DraftDao` for local draft.
    2. Check `ArtifactRepository` for published artifact (Firestore).
    3. Return a unified `Artifact` model (using a helper to map `ArtifactDraftEntity` to `Artifact`).

---

### 2. UI Layer: Player State & UI

Introduce explicit loading states and error handling in the Global Player.

#### [PlayerViewModel.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/ui/player/PlayerViewModel.kt)

- Add `PlayerLoadState` enum: `IDLE`, `LOADING`, `LOADED`, `ERROR`.
- Update `playArtifactById(id: String)` to:
    1. Set `loadState = LOADING` and `isExpanded = true`.
    2. Call `PlayableArtifactRepository.resolveArtifact(id)`.
    3. On Success: `playArtifact(artifact)`, `loadState = LOADED`.
    4. On Failure: `loadState = ERROR`.
- Add Analytics logging for `review_artifact_tapped`, `player_opened`, `artifact_loaded`.

#### [ArtifactPlayerView.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/ui/player/ArtifactPlayerView.kt)

- Remove early return on `currentArtifact == null`.
- Use `uiState.loadState` to drive the UI:
    - `LOADING`: Show cinematic shimmer/pulse over `Obsidian950` background.
    - `ERROR`: Show "Unable to load artifact" with a **Retry** button.
    - `LOADED`: Show `ImmersivePlayerScreen`.

---

### 3. Analytics & Tracking

#### [PlaybackAnalyticsManager.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/audio/PlaybackAnalyticsManager.kt)

- Add new tracking methods:
    - `trackReviewTapped(artifactId: String)`
    - `trackArtifactLoaded(artifact: Artifact, source: String)`
    - `trackReviewComplete(artifactId: String)` (already exists, but verify usage).

---

### 4. Navigation & Flow

#### [RecordingNavigation.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/navigation/features/RecordingNavigation.kt)

- Maintain `onReview` navigating to `Home`.
- Verify that `playArtifactById` is called *before* navigation to ensure the state transition begins immediately.

---

## Verification Plan

### Automated Tests
- **New Unit Test**: `PlayableArtifactRepositoryTest` to verify resolution logic for both Drafts and Published artifacts.
- **Regression**: Verify `PlayerViewModelTest` for standard playback flows.

### Manual Verification
1.  **Recording Flow**: Record -> Tap "Review".
    *   **Verify**: Player expands *immediately* (animation starts before navigation completes).
    *   **Verify**: Loading state is visible for a moment.
    *   **Verify**: Audio starts automatically.
    *   **Verify**: Home screen is visible behind the player when collapsed.
2.  **Error Handling**: Simulate a missing draft file (e.g., delete file manually).
    *   **Verify**: Player shows "Unable to load" with Retry button.
3.  **Draft List**: Tap "Review" on an existing draft.
    *   **Verify**: Resolves correctly via the new repository.
4.  **Offline Support**: Turn off network and review a local draft.
    *   **Verify**: Resolution succeeds via `DraftDao` without attempting network.
5.  **Analytics**: Check Logcat for analytics events during the flow.
