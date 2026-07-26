# Phase 1: Critical Firestore Aggregate Integrity Implementation Plan

This plan implements server-authoritative aggregate counters for `commentCount` and `playCount`, and ensures proper initialization of `safetyConcernCount` in accordance with the approved ADR.

## User Review Required

> [!IMPORTANT]
> The implementation strictly follows the ADR. No architectural changes are proposed.
> Security rules will be updated to enforce zero-trust for the new `artifact_plays` collection.

## Proposed Changes

### [Cloud Functions] (functions/src/index.ts)

#### [MODIFY] [index.ts](file:///F:/Android Project/01/functions/src/index.ts)
- Add `onCommentCreated` trigger to increment `commentCount`.
- Add `onCommentDeleted` trigger to decrement `commentCount`.
- Add `onPlayCreated` trigger to increment `playCount`.
- Update `onArtifactCleanupTrigger` to include cleanup for `artifact_plays`.
- All triggers will use `withIdempotency(context.eventId)`.

### [Android App] (app/src/main/java/com/saurabh/artifact/repository)

#### [MODIFY] [ArtifactEngagementRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactEngagementRepository.kt)
- Update `recordPlay` to write a daily bucketed event to `artifact_plays` collection.
- Document ID format: `play_{userId}_{artifactId}_{YYYY-MM-DD}`.

#### [MODIFY] [ArtifactPublishingRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactPublishingRepository.kt)
- Update `mapArtifactToFirestoreData` to initialize `safetyConcernCount` and `commentCount` to `0`.

### [Firestore Rules] (firestore.rules)

#### [MODIFY] [firestore.rules](file:///F:/Android Project/01/firestore.rules)
- Add security rules for `artifact_plays` collection:
    - Allow `create` and `read` for authenticated users.
    - Prevent `update` and `delete`.
    - Ensure `userId` in document matches `request.auth.uid`.

## Aggregate Invariants

The following conditions must remain true throughout the implementation:

### commentCount
- Must never be negative.
- Must only be modified by Cloud Functions.
- Must equal the number of documents in `artifacts/{artifactId}/comments` (with status ACTIVE/MODERATED).

### playCount
- Must never be negative.
- Must only be modified by Cloud Functions.
- Must increment at most once per `(userId, artifactId, UTC day)`.
- Duplicate Cloud Function retries must not change the count.
- Client retries must not change the count.

### safetyConcernCount
- Must exist on every published Artifact.
- Initial value must always be `0`.
- Must never be omitted during artifact creation.

### Cleanup
Deleting an Artifact must leave:
- no orphaned `artifact_plays`
- no orphaned aggregate state
- no orphaned comment aggregates

## Verification Plan

### Automated Tests
- **Cloud Functions**:
    - Add/Update Jest tests to verify `onCommentCreated`, `onCommentDeleted`, and `onPlayCreated` triggers.
    - Verify idempotency using mocked `idempotency_keys` collection.
    - Verify cleanup logic in `onArtifactCleanupTrigger`.
- **Android App**:
    - Run `ArtifactEngagementRepositoryTest` and `ArtifactPublishingRepositoryTest` to ensure no regressions.

### Manual Verification
- Deploy Cloud Functions to Firebase Emulator and verify triggers via Firestore UI.
- Verify security rules using Firestore Emulator.
