# Implementation Plan: Robust Moderation & Feed Refresh

The current moderation system has gaps where reported content remains visible to the reporter, and reported artifacts aren't filtered from the main feed. This plan implements immediate local hiding for reporters, cross-feed filtering, and reliable UI refresh triggers.

## Proposed Changes

### 1. Local Data Hardening
Add moderation fields to the local cache to ensure reported content stays hidden even when offline or during paging reloads.

#### [ArtifactEntity.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/ArtifactEntity.kt)
- Add `reportCount: Int` and `reporterIds: List<String>` fields.

#### [AppDatabase.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/AppDatabase.kt)
- Increment version to `40`.
- Add `MIGRATION_39_40` to add `reportCount` and `reporterIds` columns to `artifacts` table.

---

### 2. Paging & Filtering Logic
Ensure both comment and artifact feeds respect moderation thresholds and personal report history.

#### [CommentPagingSource.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/paging/CommentPagingSource.kt)
- Update filtering to check if `currentUserId` is in `reporterIds`.
- Hide comment immediately if reported by current user or `reportCount >= 3`.

#### [ArtifactRemoteMediator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/paging/ArtifactRemoteMediator.kt)
- Filter artifacts from network response before saving to Room.
- Hide if `reportCount >= 3` or `reporterIds` contains `currentUserId`.
- *Note*: Requires passing `currentUserId` to the mediator.

#### [ArtifactPagingSource.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/paging/ArtifactPagingSource.kt)
- Update same filtering logic as `ArtifactRemoteMediator`.

---

### 3. Repository & ViewModel Hardening
Refine how reports are submitted and how the UI reacts to them.

#### [ArtifactRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt)
- Update `submitReport`: After Firestore update, update the local `ArtifactEntity` in Room to include the reporter's ID and increment `reportCount`.

#### [CommentViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/CommentViewModel.kt)
- Replace the `null` assignment refresh hack with a `MutableSharedFlow<Unit>` refresh trigger to avoid `StateFlow` conflation issues.

---

### 4. UI Feedback Logic
Update how the feed handles reported items.

#### [FeedViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/FeedViewModel.kt)
- Ensure `reportArtifact` also triggers a local Room update for immediate hiding without waiting for network.

## Verification Plan

### Automated Tests
- `ModerationFilteringTest`: Mock artifacts/comments with varying `reportCount` and `reporterIds` and verify they are filtered correctly in `PagingSource` and `RemoteMediator`.
- `RoomModerationTest`: Verify that `ArtifactRepository.submitReport` correctly updates local Room DB.

### Manual Verification
1. **Immediate Hiding**: Report a comment. Verify it disappears from the list *immediately* without a manual pull-to-refresh.
2. **Offline Persistence**: Report an artifact. Close the app, disable internet, and reopen. Verify the artifact is still hidden.
3. **Threshold Trigger**: Report a comment from User A. Log in as User B and User C and report the same. Verify it disappears for User D (who hasn't reported it) once the count hits 3.
