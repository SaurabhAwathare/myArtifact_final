# Implementation Plan - Artifact Comment System Compose UI

Implement the user interface for the Artifact Comment System using Jetpack Compose, integrating with the existing `CommentViewModel` and following Artifact's design philosophy. This phase focuses solely on building reusable UI components.

## User Review Required

- **Bottom Sheet Pattern**: Using a Material 3 `ModalBottomSheet` for the main container to ensure better system integration (drag gestures, accessibility) and consistency with modern Android UI patterns.
- **Empty State**: Using a calm, minimal design as requested: "No comments yet. Be the first to thoughtfully respond."

## Proposed Changes

### UI Components

#### [NEW] [CommentSheet.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/components/CommentSheet.kt)
- The main entry point for the comment UI.
- Wraps `CommentList` and `CommentComposer` in a Material 3 `ModalBottomSheet`.
- Observes `CommentUiState` and `CommentUiEvent` from `CommentViewModel`.
- Handles events to clear input or show confirmations.

#### [NEW] [CommentList.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/components/CommentList.kt)
- A `LazyColumn` that displays comments in chronological order.
- Implements manual pagination by triggering `loadNextPage()` when the user scrolls near the end.
- Handles empty, loading, and error states using existing components like `AppEmptyState` and `CircularProgressIndicator`.

#### [NEW] [CommentItem.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/components/CommentItem.kt)
- Displays individual comments with `ArtifactAvatar`, name, sigil, text, and relative timestamp.
- Shows a delete button if the comment belongs to the current user.

#### [NEW] [CommentComposer.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/components/CommentComposer.kt)
- A specialized text input area using `MindfulTextField`.
- Includes a submit button and loading state.
- Bindings to `CommentViewModel` for text updates and submission.

---

## Verification Plan

### Automated Tests
- Run build to ensure no compilation errors:
```bash
./gradlew :app:assembleDebug
```

### Manual Verification
- **Compose Previews**: Create previews for `CommentItem`, `CommentComposer`, and `CommentList` (with mock data) to verify visual design.
- **Layout Inspection**: Use `render_compose_preview` to verify the components look correct.
- **State Flow**: Verify (via code audit) that `CommentViewModel` states are correctly mapped to UI components.
