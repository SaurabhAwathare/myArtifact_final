# Implementation Plan - Firestore Rule Predicate Runtime Verification

This document outlines the investigation strategy to identify the exact Firestore security rule predicate causing `PERMISSION_DENIED` and the subsequent application crash when the comment button is pressed.

## User Review Required

> [!IMPORTANT]
> This investigation is **read-only**. No application code or Firestore rules will be modified.

> [!WARNING]
> The application crash is suspected to be caused by an unhandled exception in the `CommentViewModel`'s engagement status observation flow.

## Open Questions

- None at this stage. Static analysis has provided enough leads for the initial evidence gathering.

## Proposed Investigation Steps

### Phase 1 – Trace the Firestore Request

We have identified three primary Firestore operations involved in the comment flow:

1.  **Engagement Observation (Read)**
    - **Path**: `users/{userId}/engagement/{artifactId}`
    - **Operation**: `SnapshotListener` (get/listen)
    - **Trigger**: `CommentViewModel.init` or `initialize()`
    - **Code**: [FirestoreEngagementRepository.kt:L48](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/FirestoreEngagementRepository.kt#L48)

2.  **Engagement Sync (Write)**
    - **Path**: `users/{userId}/engagement/{artifactId}`
    - **Operation**: `set` with `SetOptions.merge()`
    - **Payload**: `artifactId`, `userId`, `version`, `totalDurationMs`, `audioChecksum`, `coverage`, `lastPositionMs`, `furthestPositionMs`, `hasReachedEnd`, `updatedAt`
    - **Trigger**: `InteractionSyncWorker.syncEngagement`
    - **Code**: [FirestoreEngagementRepository.kt:L133](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/FirestoreEngagementRepository.kt#L133)

3.  **Comment Submission (Write)**
    - **Path**: `artifacts/{artifactId}/comments/{commentId}`
    - **Operation**: `set`
    - **Payload**: `artifactId`, `creatorId`, `author`, `text`, `createdAt`, `updatedAt`, `status`
    - **Trigger**: `CommentViewModel.submitComment`
    - **Code**: [FirestoreCommentRepository.kt:L71](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/FirestoreCommentRepository.kt#L71)

### Phase 2 – Trace Authentication

The investigation will track the following timeline:

1.  **Auth State at ViewModel Init**: Verify if `FirebaseAuth.getInstance().currentUser` is non-null when `observeUnlockStatus()` is called.
2.  **UID Consistency**: Compare `request.auth.uid` with the `userId` used in the Firestore paths.
3.  **Auth State Changes**: Monitor if the auth state transitions during the listener attachment.

**Suspected Mismatch**: `PERMISSION_DENIED` on the listener suggests `request.auth.uid != userId`.

### Phase 3 – Rule Evaluation

We will evaluate the predicates for the failing engagement read rule:

**Rule Target**: `match /users/{uid}/engagement/{artifactId}`

| Expression | Expected Value | Actual Value | Evaluation |
| :--- | :--- | :--- | :--- |
| `request.auth != null` | `true` | `true` (if signed in) | TRUE |
| `request.auth.uid == uid` | `userId == userId` | `? == ?` | **SUSPECTED FALSE** |

**Suspected Cause**: Mismatch between the UID captured in Kotlin and the UID in the Firestore request context.

### Phase 4 – Listener Investigation

The `UNLOCK_OBSERVATION_FAILED` log in `FirestoreEngagementRepository` confirms that the listener on `users/{userId}/engagement/{artifactId}` is the first failure point.

- **Failing Rule**: `allow read: if isOwner(uid);`
- **Failing Predicate**: `isOwner(uid)` (specifically `request.auth.uid == uid`).

### Phase 5 – Crash Investigation

The crash occurs because the Firestore exception propagates unhandled:

1.  **Firestore**: Returns `PERMISSION_DENIED` to the listener.
2.  **Repository**: Logs `UNLOCK_OBSERVATION_FAILED` and calls `close(error)` on the `callbackFlow`.
3.  **Flow**: Propagates the exception to the collector.
4.  **ViewModel**: `collectLatest` in `observeUnlockStatus()` rethrows the exception.
5.  **Coroutine**: `viewModelScope.launch` fails, leading to an application crash.

## Verification Plan

### Automated Tests
- Run `firestore-tests/engagement_rules.test.js` to verify rule logic under various auth states.

### Manual Verification
- Inspect `INVESTIGATION_LOG` outputs in Logcat (if accessible) for `AuthUID` vs `PathUID`.
- Verify the `artifactId` value passed to the ViewModel via `SavedStateHandle`.
