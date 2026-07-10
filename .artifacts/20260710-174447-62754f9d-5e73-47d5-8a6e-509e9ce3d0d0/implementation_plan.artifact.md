# Implementation Plan - Phase 6: Player Integration (Revised)

Integrate the Artifact Comment System into the Immersive Player experience using a decoupled architectural approach. This involves a Comment icon in the interaction bar, a stateless `CommentSheet`, and a `CommentSheetHost` to manage ViewModel initialization and state observation.

## Proposed Changes

### 1. Player UI Enhancements

#### [PlayerInteractionBar.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/player/components/PlayerInteractionBar.kt)
- Add `onCommentClick: () -> Unit` parameter.
- Add `isCommentEnabled: Boolean = true` and `commentDisabledReason: String? = null`.
- Add an `InteractionItem` for "Comment" using `Icons.Rounded.ChatBubbleOutline`.

#### [ImmersivePlayerScreen.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/player/ImmersivePlayerScreen.kt)
- Add `onCommentClick: () -> Unit` parameter.
- Pass `uiState.isThresholdMet` and the explanation ("Listen to at least 95% of this Artifact before joining the conversation.") to `PlayerInteractionBar`.

---

### 2. Comment System Refactoring

#### [CommentViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/CommentViewModel.kt)
- Add an `initialize(id: String)` function to allow setting the `artifactId` when not provided via `SavedStateHandle`.
- Ensure it only triggers loading if the ID has actually changed or was previously empty.

#### [CommentSheet.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/components/CommentSheet.kt)
- Refactor to be a **Stateless UI Component**.
- Remove `hiltViewModel()` dependency.
- Accept `uiState`, `events`, and callbacks (`onSubmit`, `onDelete`, `onLoadNextPage`, `onRetry`, `onDismiss`) as parameters.
- Retain local UI state like `inputText` and `sheetState`.

```kotlin
@Composable
fun CommentSheet(
    uiState: CommentUiState,
    events: SharedFlow<CommentUiEvent>,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onDelete: (Comment) -> Unit,
    onLoadNextPage: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ... UI implementation only ...
}
```

#### [NEW] [CommentSheetHost.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/components/CommentSheetHost.kt)
- Acts as the integration bridge between the Player and the Comment logic.
- **Responsibilities**:
    - Obtain `CommentViewModel` via `hiltViewModel()`.
    - Call `viewModel.initialize(artifactId)` in a `LaunchedEffect`.
    - Collect `uiState` and pass it down.
    - Map ViewModel actions to `CommentSheet` callbacks.

```kotlin
@Composable
fun CommentSheetHost(
    artifactId: String,
    onDismiss: () -> Unit,
    viewModel: CommentViewModel = hiltViewModel()
) {
    LaunchedEffect(artifactId) {
        viewModel.initialize(artifactId)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    CommentSheet(
        uiState = uiState,
        events = viewModel.events,
        onDismiss = onDismiss,
        onSubmit = viewModel::submitComment,
        onDelete = { /* Show confirmation dialog before calling VM */ },
        onLoadNextPage = viewModel::loadNextPage,
        onRetry = viewModel::loadInitialComments
    )
}
```

---

### 3. Orchestration

#### [ArtifactPlayerView.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/player/ArtifactPlayerView.kt)
- Add `showCommentSheet` boolean state.
- In `ImmersivePlayerScreen`, implement `onCommentClick = { showCommentSheet = true }`.
- Conditionally render `CommentSheetHost` when `showCommentSheet` is true and an artifact is present.

```kotlin
if (showCommentSheet && uiState.currentArtifact != null) {
    CommentSheetHost(
        artifactId = uiState.currentArtifact.id,
        onDismiss = { showCommentSheet = false }
    )
}
```

---

### 4. UX Refinements
- **Delete Confirmation**: Implement a `MaterialAlertDialog` within `CommentSheetHost` (or `CommentSheet`) before executing `viewModel.deleteComment(comment)`.
- **Submission Feedback**: Ensure `CommentSheet` observes the `CommentSubmitted` event to clear the local `inputText`.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to verify compilation and dependency injection.

### Manual Verification
1.  **Gated Access**: Verify "Comment" button is disabled with toast explanation before 95% threshold, and becomes enabled after.
2.  **Stateless Launch**: Verify `CommentSheetHost` correctly initializes the VM and loads the correct artifact's comments.
3.  **Submission**: Verify input clears on success and comment appears in list.
4.  **Deletion**: Verify the confirmation dialog appears and deletion refreshes the list.
5.  **Continuity**: Verify audio continues playing without glitches while the sheet is open or being interacted with.
6.  **Error Handling**: Verify "Retry" works in the empty/error state.
