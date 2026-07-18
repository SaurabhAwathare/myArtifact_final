# Walkthrough - Community Report Aggregation Pipeline (Phase 2)

Successfully implemented the backend aggregation pipeline for community reports. This phase establishes Cloud Functions as the authoritative moderation processor, ensuring that report counts are derived from the canonical source of truth and artifacts are suppressed from recommendation feeds when necessary.

## Changes Made

### Cloud Functions

#### [NEW] [config.ts](file:///F:/Android Project/01/functions/src/util/moderation/config.ts)
- Defined `ModerationConfig` with a `REPORT_SUPPRESSION_THRESHOLD` of `3`.
- Added constants for `RecommendationState` (`ACTIVE`, `SUPPRESSED`) and `ModerationStatus`.

#### [MODIFY] [index.ts](file:///F:/Android Project/01/functions/src/index.ts)
- Implemented `onReportCreated` trigger for the `reports/{reportId}` collection.
- Structured the function into distinct steps:
    1. **Idempotency**: Using `withIdempotency` to prevent duplicate processing.
    2. **Aggregation**: `aggregateReports` helper calculates `reportCount` and `lastReportedAt` directly from the `reports` collection (the source of truth).
    3. **Evaluation**: `evaluateModerationState` determines if the artifact should be suppressed.
    4. **Update**: Atomic update of the Artifact document with derived metadata.
    5. **Queueing**: Creates/updates a `moderation_queue` entry for manual review.

### Firestore Security Rules

#### [MODIFY] [firestore.rules](file:///F:/Android Project/01/firestore.rules)
- **Protected Metadata**: Added `reportCount` and `recommendationState` to the list of fields that cannot be modified by users (including the artifact owner).
- **Moderation Queue**: Added rules for the `moderation_queue` collection, allowing only global admins to read and completely blocking client-side writes.

## Verification Results

### Automated Logic
- **Derived Count**: The pipeline correctly ignores duplicate reports from the same user (due to Phase 1's unique document ID `reporterId_artifactId`) and recalculates the total count from the collection.
- **Suppression**: Once `reportCount >= 3`, the `recommendationState` is set to `SUPPRESSED`.
- **Idempotency**: Retrying the function (e.g., on failure) is safe due to the `idempotency_keys` check and atomic batch updates.

### Manual Simulation
- Verified that `isPublic` remains unchanged during suppression, ensuring the artifact is still accessible via direct link but hidden from feeds.
- Confirmed that the `moderation_queue` entry is updated with each new report, keeping it at the top of the pending list.

> [!NOTE]
> The `reports` collection remains the canonical source of truth. The `reportCount` on the Artifact is derived metadata and can be safely recalculated at any time by re-running the aggregation logic.
