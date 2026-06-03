# Implementation Plan: Hardening Report & Moderation System

The current reporting system successfully handles artifacts but has significant gaps in comment moderation. This plan hardens the system by synchronizing data models and implementing threshold-based auto-hiding for toxic comments.

## Proposed Changes

### 1. Data Model Synchronization
Add necessary fields to track moderation state directly on comment documents for performance.

#### [CommentModels.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/model/CommentModels.kt)
- Add `reportCount` and `reporterIds` to `ArtifactComment` data class.
- Update `CommentModerationState` to include `HIDDEN` (for threshold triggers).

```kotlin
data class ArtifactComment(
    // ... existing fields
    val reportCount: Int = 0,
    val reporterIds: List<String> = emptyList(), // Store IDs to prevent double-reporting
    val moderationState: CommentModerationState = CommentModerationState.PENDING,
)
```

---

### 2. Comment Filtering Logic
Ensure reported comments are actually removed from the user interface.

#### [CommentPagingSource.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/paging/CommentPagingSource.kt)
- Update the `load` function to check `reportCount`.
- Hide comments if `reportCount >= 3` or `moderationState == BLOCKED`.

```kotlin
val visible = when (comment.visibilityLayer) {
    // ... existing visibility logic
} && comment.reportCount < 3 && comment.moderationState != CommentModerationState.BLOCKED
```

---

### 3. Repository Hardening
Refine the submission logic to use better anonymous identifiers and prevent duplicate reports.

#### [ArtifactRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt)
- Update `submitReport` to add the reporter's ID to `reporterIds` array.
- Use `Settings.Secure.ANDROID_ID` (hashed) or Firebase Installation ID for `deviceId` instead of hardcoded `0`.

---

### 4. UI Feedback
Provide immediate feedback when a user reports a comment.

#### [CommentViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/CommentViewModel.kt)
- Update `submitReport` to trigger a local UI refresh or show a "Content Hidden" placeholder after reporting.

## Verification Plan

### Automated Tests
- `CommentRepositoryTest`: Verify that `submitReport` increments `reportCount` and adds to `reporterIds`.
- `CommentPagingSourceTest`: Mock comments with high report counts and verify they are filtered out.

### Manual Verification
1. **Reporting Flow**: Report a comment as a user. Verify it disappears from the reflection list immediately.
2. **Threshold Check**: Log in as different users and report the same comment. Verify it disappears for *everyone* once the threshold is hit.
3. **Anonymity Check**: Verify the `reports` collection in Firestore does not contain PII (only hashed IDs).
