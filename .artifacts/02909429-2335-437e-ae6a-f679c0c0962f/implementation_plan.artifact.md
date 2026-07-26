# Phase 1: Critical Firestore Aggregate Integrity Implementation Plan

Objective: Implement server-authoritative aggregation for `commentCount` and `playCount`, and ensure consistent initialization for `safetyConcernCount`.

## User Review Required

> [!IMPORTANT]
> **Play Tracking Collection**: I will introduce a new top-level collection `artifact_plays` to track individual play events. This allows for idempotent aggregation and protects against replay attacks by using a composite ID `${userId}_${artifactId}_${timestamp_bucket}`.

> [!NOTE]
> **Cloud Functions**: New triggers will be added to `functions/src/index.ts`. I will ensure they follow the existing pattern of using `FieldValue.increment()` for atomic updates.

## Proposed Changes

### 1. Comment Aggregation (Backend)

#### [MODIFY] [index.ts](file:///F:/Android Project/01/functions/src/index.ts)
- Add `onCommentCreated` trigger on `artifacts/{artifactId}/comments/{commentId}`.
- Add `onCommentDeleted` trigger on `artifacts/{artifactId}/comments/{commentId}`.
- These triggers will increment/decrement the `commentCount` field in the parent artifact document.

### 2. Play Aggregation (Mobile & Backend)

#### [MODIFY] [ArtifactEngagementRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactEngagementRepository.kt)
- Update `recordPlay` signature to `suspend fun recordPlay(userId: String?, artifactId: String, emotion: String)`.
- Implement logic to write a play event document to the `artifact_plays` collection.

#### [MODIFY] [ArtifactRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactRepository.kt)
- Update `recordPlay` delegate to pass `artifactId`.

#### [MODIFY] [FeedViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/FeedViewModel.kt)
- Update call to `recordPlay` to include `artifact.id`.

#### [MODIFY] [index.ts](file:///F:/Android Project/01/functions/src/index.ts)
- Add `onPlayCreated` trigger on `artifact_plays/{playId}`.
- Increment `playCount` in the corresponding artifact document.
- Update `onArtifactCleanupTrigger` to include cleanup for `artifact_plays`.

#### [MODIFY] [firestore.rules](file:///F:/Android Project/01/firestore.rules)
- Add security rules for the `artifact_plays` collection, allowing authenticated users to create their own play records.

### 3. Schema Consistency (Mobile)

#### [MODIFY] [ArtifactPublishingRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactPublishingRepository.kt)
- Update `mapArtifactToFirestoreData` to explicitly initialize `safetyConcernCount` and `commentCount` to 0.

---

## Verification Plan

### Automated Tests
- **Unit Tests (Kotlin)**:
  - Update `ArtifactEngagementRepositoryTest` to verify `recordPlay` writes the correct document.
  - Update `ArtifactPublishingRepositoryTest` to verify initialization of new fields.
- **Integration Tests (Cloud Functions)**:
  - Add Jest tests in a new file `functions/src/__tests__/aggregates.test.ts` (or similar) to verify triggers correctly update artifact counts using the Firestore emulator.

### Manual Verification
- Deploy functions to emulator and verify:
  1. Adding a comment increments `commentCount`.
  2. Deleting a comment decrements `commentCount`.
  3. Playing an artifact creates an `artifact_plays` record and increments `playCount`.
  4. Creating a new artifact has `safetyConcernCount: 0` and `commentCount: 0`.
