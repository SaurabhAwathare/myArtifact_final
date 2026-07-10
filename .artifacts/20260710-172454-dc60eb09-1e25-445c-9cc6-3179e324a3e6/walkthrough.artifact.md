# Walkthrough - Comment System ViewModels & State Management

I have implemented the ViewModels and state management for the Artifact Comment System. This phase connects the Domain Layer to the Presentation Layer, preparing the system for the upcoming UI implementation.

## Changes

### Presentation Layer

#### [CommentUiState.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/CommentUiState.kt)
- Defines a single immutable state model for the comment UI.
- Tracks loading states (initial, pagination, refresh), error states, and submission status.
- **Improvement**: Kept the pagination cursor (`DocumentSnapshot`) internal to the ViewModel to avoid leaking implementation details to the UI.

#### [CommentUiEvent.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/CommentUiEvent.kt)
- Defines one-time events for the UI.
- **Improvement**: Included `CommentSubmitted` and `CommentDeleted` as events to handle UI actions like clearing input or showing confirmations without persisting success state in the `UiState`.

#### [CommentViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/CommentViewModel.kt)
- Orchestrates `GetCommentsUseCase`, `AddCommentUseCase`, and `DeleteCommentUseCase`.
- Manages pagination logic, merging new comments into the existing list.
- Exposes state via `StateFlow` and events via `SharedFlow`.
- Handles `artifactId` from `SavedStateHandle`.

## Verification Summary

### Automated Tests
- Ran `./gradlew :app:assembleDebug` and the build finished successfully, confirming that all new classes compile and dependency injection (Hilt) resolves correctly.

### Manual Inspection
- **Architecture Audit**: Verified that `CommentViewModel` contains no business logic. It delegates validation to `CommentValidator` (via `AddCommentUseCase`) and Firestore operations to `CommentRepository`.
- **State Audit**: Confirmed that all state transitions (loading -> success/failure) are handled correctly within the ViewModel's coroutine scopes.
- **Pagination Logic**: Verified that `lastVisibleCursor` is updated correctly and `hasMorePages` is derived from the repository's response.
