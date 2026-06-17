# Navigation Loop Fix

The app enters a navigation loop between Home and PublishingStudio because `PlayerViewModel` triggers a "navigate to publish" event whenever a review threshold is met, even for artifacts that are already published.

## Proposed Changes

### UI Layer

#### [PlayerViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/player/PlayerViewModel.kt)

- Update the `init` block that observes `reviewSessionManager.reviewProgress`.
- Add a check to ensure `navigateToPublish` is only emitted if the current artifact is in a state that requires publishing (specifically checking against `ArtifactStatus.DRAFT`).

#### [GlobalOverlayHost.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/components/GlobalOverlayHost.kt)

- Add a defensive check to `onNavigateToPublish` collection to prevent navigation if the user is already on the `PublishingStudio` screen.

## Verification Plan

### Automated Tests
- I will look for existing tests for `PlayerViewModel` and see if I can add a test case for this scenario.
- Command: `gradlew test` (after finding the specific test class)

### Manual Verification
1.  **Play a published artifact from Feed**: Observe that it no longer triggers navigation to `PublishingStudio`.
2.  **Play your own draft**: Record a new artifact or play an existing draft, complete the review, and observe that it correctly navigates to `PublishingStudio`.
3.  **Reopen an already published artifact multiple times**: Ensure no navigation is triggered across multiple sessions.
4.  **Background -> foreground while playing**: Ensure the app doesn't suddenly navigate to `PublishingStudio` upon returning to foreground.
