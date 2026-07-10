# Implementation Plan - Comment System ViewModels & State Management

Implement the `CommentViewModel` and `CommentUiState` to manage the lifecycle of artifact comments, connecting the Domain Layer to the Presentation Layer.

## User Review Required

> [!NOTE]
> The `artifactId` will be retrieved from the `SavedStateHandle`. This assumes that the navigation component passes "artifactId" as a string argument when navigating to the comment screen/bottom sheet.

## Proposed Changes

### Presentation Layer (UI Models & ViewModel)

Create the necessary classes for managing comment state in the `ui.comment` package.

#### [NEW] [CommentUiState.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/CommentUiState.kt)

- Defines the immutable state for the comment system.
- Includes fields for comments, loading states (initial, pagination, refresh), error states, and submission status.

```kotlin
data class CommentUiState(
    val comments: List<Comment> = emptyList(),
    val isInitialLoading: Boolean = false,
    val isLoadingNextPage: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: AppError? = null,
    val isSubmitting: Boolean = false,
    val submissionSuccess: Boolean = false,
    val submissionError: AppError? = null,
    val lastVisible: DocumentSnapshot? = null,
    val hasMorePages: Boolean = true
) {
    val isEmpty: Boolean get() = comments.isEmpty() && !isInitialLoading
}
```

#### [NEW] [CommentUiEvent.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/CommentUiEvent.kt)

- Defines one-time events for the comment UI (e.g., scrolling to top after submission).

```kotlin
sealed class CommentUiEvent {
    object CommentSubmitted : CommentUiEvent()
}
```

#### [NEW] [CommentViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/CommentViewModel.kt)

- Orchestrates `GetCommentsUseCase`, `AddCommentUseCase`, and `DeleteCommentUseCase`.
- Manages pagination logic, preventing duplicate requests and merging results.
- Exposes `uiState` as a `StateFlow` and `events` as a `SharedFlow`.
- Handles `artifactId` from `SavedStateHandle`.

---

## Verification Plan

### Automated Tests
- I will run `./gradlew app:assembleDebug` to ensure everything compiles correctly.
- I will verify Hilt registration by checking for compilation errors related to DI.

### Manual Verification
- **Code Inspection**: Verify that no business logic exists in the ViewModel and that all validation is delegated to the Domain Layer.
- **State Flow Audit**: Review the ViewModel actions to ensure loading states are correctly toggled and errors are captured as `AppError`.
- **Pagination Logic Audit**: Ensure `lastVisible` cursor is updated and `hasMorePages` is correctly determined.
