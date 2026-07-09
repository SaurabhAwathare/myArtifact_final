# Implementation Plan - Phase 2: Full Player Anonymous Profile Navigation

Implement anonymous profile navigation from the Full Player by enabling taps on the Artifact Creator's avatar and anonymous name in the immersive player screen.

## Proposed Changes

### UI Components

#### [ImmersivePlayerScreen.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/player/ImmersivePlayerScreen.kt)
- Add `onAuthorClick: (String) -> Unit` callback.
- Apply `Modifier.clickable { onAuthorClick(uiState.internalOwnerId) }` to:
    - `ArtifactAvatar`
    - The `Text` displaying `authorName`.

#### [ArtifactPlayerView.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/player/ArtifactPlayerView.kt)
- Add `onAuthorClick: (String) -> Unit` callback.
- Pass `onAuthorClick` through to `ImmersivePlayerScreen`.

---

### Navigation

#### [GlobalOverlayHost.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/components/GlobalOverlayHost.kt)
- Update the `ArtifactPlayerView` instantiation to include the `onAuthorClick` logic.
- Use `navController.navigate(Profile(userId))` to trigger the transition.

## Verification Plan

### Automated Tests
- Run build to ensure no compilation errors:
  ```powershell
  ./gradlew assembleDebug
  ```

### Manual Verification
1. Tap the creator's avatar in the Full Player.
   - Expected: The correct anonymous profile opens.
2. Return to the player.
3. Tap the creator's anonymous name in the Full Player.
   - Expected: The same profile opens.
4. Press Back.
   - Expected: Return to the Full Player.
5. Tap your own Artifact in the player.
   - Expected: Your own profile opens correctly.
