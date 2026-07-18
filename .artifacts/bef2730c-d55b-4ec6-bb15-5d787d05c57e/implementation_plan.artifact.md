# Implementation Plan - Community Report Aggregation Pipeline (Phase 2)

Implement the backend aggregation pipeline for community reports. This includes a Cloud Function trigger to update artifact metadata and a moderation queue, while ensuring idempotency and data privacy.

## User Review Required

> [!IMPORTANT]
> - **Suppression Threshold**: I am setting `ModerationConfig.REPORT_SUPPRESSION_THRESHOLD` to `3`. Please let me know if a different value is preferred.
> - **Recommendation State**: Only the `recommendationState` field will be modified for suppression. Visibility (`isPublic`) remains unchanged.

## Proposed Changes

### [Backend] Cloud Functions

#### [NEW] [config.ts](file:///F:/Android Project/01/functions/src/util/moderation/config.ts)
- Define `ModerationConfig` with `REPORT_SUPPRESSION_THRESHOLD`.

#### [MODIFY] [index.ts](file:///F:/Android Project/01/functions/src/index.ts)
- Implement `onReportCreated` trigger:
    - Listen to `reports/{reportId}`.
    - Validate `artifactId`.
    - Use `withIdempotency` for safe processing.
    - Use a Firestore transaction to:
        - **Aggregate**: Calculate the current `reportCount` and `lastReportedAt` based on the `reports` collection (canonical source of truth).
        - **Evaluate**: Check if the derived `reportCount` meets the `REPORT_SUPPRESSION_THRESHOLD`.
        - **Update**: Apply derived metadata to the Artifact document (`reportCount`, `lastReportedAt`, `recommendationState`).
        - **Queue**: Create or update the `moderation_queue` entry if needed.
    - Add structured logging.

### [Component Name]

#### [NEW] [config.ts](file:///F:/Android Project/01/functions/src/util/moderation/config.ts)
#### [MODIFY] [index.ts](file:///F:/Android Project/01/functions/src/index.ts)
#### [MODIFY] [firestore.rules](file:///F:/Android Project/01/firestore.rules)

## Verification Plan

### Automated Tests
- I will check if I can run the existing `firestore-tests` and potentially add a new test case for report aggregation if the environment allows.
- Specifically, verify:
    - `reportCount` reflects the actual number of reports in the collection.
    - Duplicate reports (same user/artifact) are blocked by Firestore rules (Phase 1 legacy) or ignored by CF (Phase 2).
    - Threshold suppression sets `recommendationState` to `SUPPRESSED`.
    - `isPublic` and `moderation.status` remain unaffected.

### Manual Verification
- Deploy Cloud Functions (if possible in this environment, otherwise provide code for user deployment).
- Simulate report creation via Firebase Console or script and verify Artifact updates.
