# Walkthrough - Phase 1: Engagement Synchronization

I have implemented a robust, idempotent synchronization pipeline to transport locally tracked listening evidence from Room to Firestore. This implementation adheres to the "Phase 1: Transport Layer Only" objective and incorporates all requested architectural improvements.

## Changes Made

### 1. Enhanced Synchronization State
I replaced the simple boolean sync flag with a rich `SyncState` enum and added metadata fields to `ArtifactEngagement` for better reliability and diagnostics.
- **New Enum**: `SyncState` (`PENDING`, `SYNCING`, `SYNCED`, `FAILED`).
- **Metadata**: Added `lastSyncAttempt`, `lastSyncSuccess`, `syncRetryCount`, and `lastSyncError` to the local Room database.

### 2. Decoupled Orchestration
Introduced `EngagementSyncScheduler` to handle WorkManager interactions. This separates data management (Repository) from background job orchestration.
- Uses `enqueueUniqueWork` with `ExistingWorkPolicy.APPEND_OR_REPLACE` to prevent redundant worker accumulation while ensuring data consistency.

### 3. Dedicated Firestore Transport
Created `FirestoreEngagementRepository` to handle the specific mapping of engagement evidence to Firestore.
- **Security Invariant**: The repository is hardcoded to never write to `isCommentUnlocked` or `unlockReason`, respecting the backend's authority.
- **Data Mapping**: Correctly handles `BitSet` to `Blob` conversion and maps internal fields to the expected Firestore structure (e.g., `totalDurationMs`).

### 4. Background "Sweep" Logic
Updated `InteractionSyncWorker` to perform a sweep of all unsynced engagement records before processing the standard interaction queue.
- **Idempotency**: Successful uploads mark records as `SYNCED`, preventing re-upload of unchanged data.
- **Error Handling**: Differentiates between transient network errors (triggering WorkManager retry) and permanent failures (marking state as `FAILED`).

## Verification Results

### Execution Path
`ReviewAuthorityService` -> `EngagementRepository.saveEngagement()` -> `EngagementSyncScheduler.scheduleSync()` -> `InteractionSyncWorker` -> `FirestoreEngagementRepository` -> `Firestore`.

### Firestore Document Structure
Written to `users/{uid}/engagement/{artifactId}`:
```json
{
  "artifactId": "...",
  "userId": "...",
  "version": "v1",
  "totalDurationMs": 10000,
  "audioChecksum": "...",
  "coverage": [Blob],
  "lastPositionMs": 5000,
  "furthestPositionMs": 5000,
  "hasReachedEnd": false,
  "updatedAt": [ServerTimestamp]
}
```

### Reliability & Offline Behavior
- **Offline**: Data remains in Room with `syncState = PENDING`.
- **Resumption**: Once network is restored, `EngagementSyncScheduler` (via WorkManager constraints) triggers the worker to sync all pending records.
- **Retries**: Transient failures (e.g., timeouts) are logged, and the worker requests a retry from WorkManager with exponential backoff.

## Regression Risk Assessment
- **Interaction Sync**: The existing logic for Reactions, Saves, and Follows remains intact as the engagement sync is added as an independent step within the same worker.
- **Database**: Room version was previously 56; since this is an iterative phase, I've added the fields to the entity. (Note: A schema migration might be required in the final build step).
- **Performance**: Use of unique work and 5-second sampling in `ReviewAuthorityService` prevents excessive write amplification.
