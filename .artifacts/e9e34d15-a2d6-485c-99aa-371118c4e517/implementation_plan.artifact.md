# Implementation Plan - Firestore Flow Exception Handling Hardening

This plan addresses the identified risk where Firestore `PERMISSION_DENIED` errors (and other terminal exceptions) propagated through Kotlin Flows can cause application crashes or silent flow termination. The solution involves introducing explicit exception boundaries in the ViewModel layer while preserving the Repository layer's error-preserving architecture.

## User Review Required

> [!IMPORTANT]
> This implementation will catch `FirebaseFirestoreException` in ViewModels and map them to UI error states. This prevents crashes but will result in the UI showing an error message (or entering an error state) when a permission issue occurs (e.g., due to App Check failure or signed-out state during an active listener).

> [!NOTE]
> We are not modifying the Repository layer to return "fake success" (e.g., empty lists on permission denied). The error will be handled at the consumer level as per the established architecture.

## Proposed Changes

### ViewModel Error Boundaries

#### [MODIFY] [CommentViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/CommentViewModel.kt)
- Add `.catch` operator to `engagementRepository.observeEngagementEvidence(artifactId)`.
- Map exceptions to `CommentUnlockState.ERROR`.
- Log the exception to `diagnosticLogger`.

#### [MODIFY] [ResonanceListViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/profile/ResonanceListViewModel.kt)
- Add `.catch` operator to the inner `userRepository.observeResonatingWithIds(user.uid)` flow.
- Map exceptions to `_uiState.value.error`.
- Ensure the inner flow cancellation doesn't affect the outer `currentUser` flow.

#### [MODIFY] [IdentityViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/identity/IdentityViewModel.kt)
- Add `.catch` to the `userProfile` flow definition before `.stateIn`.
- Update `_uiState` with `IdentityUiState.Error` when an exception occurs.
- Log the exception to `diagnosticLogger`.

#### [MODIFY] [ProfileViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/profile/ProfileViewModel.kt)
- Add `.catch` to the `uiState` flow definition before `.stateIn`.
- Map exceptions to the `message` property of `ProfileUiState`.

#### [MODIFY] [PlayerViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/player/PlayerViewModel.kt)
- Add `.catch` to the `metadata` flow definition before `.stateIn`.
- Report the error via `reportError`.

## Verification Plan

### Automated Verification
- Perform static analysis of Flow chains to ensure no `collect` is missing a boundary.
- Verify that `CancellationException` is NOT caught by the new `.catch` blocks (Kotlin Flow `.catch` does this by default, but we will be explicit if using `try-catch`).

### Manual Verification
- Review the diff to ensure zero changes to `repository/*.kt` files that would convert errors into default values.
- Confirm that `ErrorMessageMapper` or `DiagnosticLogger` are used consistently.
