# Fix missing `unlockState` parameter in `CommentSheet.kt`

The `CommentComposer` composable was recently updated to require an `unlockState` parameter, but the call site in `CommentSheet.kt` was not updated, leading to a compilation error.

## User Review Required

> [!NOTE]
> `CommentUiState` already contains the `unlockState` which is correctly updated in `CommentViewModel`. This change simply passes the existing state to the child component.

## Proposed Changes

### UI Components

#### [MODIFY] [CommentSheet.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/components/CommentSheet.kt)

Pass `uiState.unlockState` to `CommentComposer`.

```diff
             // Composer Area
             CommentComposer(
                 text = inputText,
                 isSubmitting = uiState.isSubmitting,
+                unlockState = uiState.unlockState,
                 onTextChanged = { inputText = it },
                 onSubmit = { onSubmit(inputText) },
                 modifier = Modifier
```

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to ensure the project compiles.

### Manual Verification
- Verify that `CommentComposer` correctly reflects the unlock state (e.g., shows "Locked" message when appropriate) in the UI.
