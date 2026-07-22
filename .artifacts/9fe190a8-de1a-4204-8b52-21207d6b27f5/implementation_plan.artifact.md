# Fix CommentViewModelTest.owner failing test

The test `owner should have UNLOCKED state immediately` is likely failing because the `engagementRepository.observeEngagementEvidence` mock (relaxed) returns an empty `Flow`. Since the `ViewModel` updates its `unlockState` only when this flow emits, it remains in the default `LOCKED` state even if `isOwner` is true.

## Proposed Changes

### [Component Name]

#### [MODIFY] [CommentViewModelTest.kt](file:///F:/Android%20Project/01/app/src/test/java/com/saurabh/artifact/ui/comment/CommentViewModelTest.kt)
- Add explicit mock for `engagementRepository.observeEngagementEvidence` in `setup()` to return `flowOf(null)`. This mimics production behavior where an initial value (or null) is emitted upon collection.

## Verification Plan

### Automated Tests
- Run the specific test:
  `./gradlew :app:testDebugUnitTest --tests "com.saurabh.artifact.ui.comment.CommentViewModelTest.owner should have UNLOCKED state immediately"`
