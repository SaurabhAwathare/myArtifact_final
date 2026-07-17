# Implementation Plan - Phase 1: Engagement Synchronization (Revised)

This plan outlines the implementation of a reliable synchronization pipeline for engagement evidence from the local Room database to Firestore using WorkManager, incorporating architectural improvements for better separation of concerns and future extensibility.

## User Review Required

> [!IMPORTANT]
> - **Sync State**: We are moving from a boolean `isSynced` to a rich `SyncState` enum (`PENDING`, `SYNCING`, `SYNCED`, `FAILED`) to support better diagnostics and retry logic.
> - **Orchestration**: A new `EngagementSyncScheduler` component will handle WorkManager interactions, keeping the `EngagementRepository` focused on data.
> - **Unique Work**: We will use `ExistingWorkPolicy.REPLACE` (or `APPEND_OR_REPLACE`) to ensure we don't spam WorkManager with identical tasks while ensuring the latest data is eventually synced.

## Proposed Changes

### [Component] Domain & Models

#### [NEW] [SyncState.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/model/SyncState.kt)
- Define `enum class SyncState { PENDING, SYNCING, SYNCED, FAILED }`.

---

### [Component] Local Data Layer (Room)

#### [MODIFY] [ArtifactEngagement.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/ArtifactEngagement.kt)
- Add `val syncState: SyncState = SyncState.PENDING`.
- Add `val lastSyncAttempt: Long = 0L`.
- Add `val lastSyncSuccess: Long = 0L`.
- Add `val syncRetryCount: Int = 0`.
- Add `val lastSyncError: String? = null`.
- Update `equals` and `hashCode`.

#### [MODIFY] [EngagementDao.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/EngagementDao.kt)
- Update `insertEngagement` to default `syncState` to `PENDING` for new/updated records.
- Add `@Query("SELECT * FROM artifact_engagement WHERE syncState = 'PENDING' OR syncState = 'FAILED'") suspend fun getEngagementsRequiringSync(): List<ArtifactEngagement>`.
- Add `@Query("UPDATE artifact_engagement SET syncState = :state, lastSyncAttempt = :timestamp, lastSyncError = :error WHERE artifactId = :artifactId") suspend fun updateSyncStatus(artifactId: String, state: SyncState, timestamp: Long, error: String? = null)`.
- Add `@Query("UPDATE artifact_engagement SET syncState = 'SYNCED', lastSyncSuccess = :timestamp, syncRetryCount = 0 WHERE artifactId = :artifactId") suspend fun markAsSynced(artifactId: String, timestamp: Long)`.

---

### [Component] Repository & Orchestration Layer

#### [NEW] [EngagementSyncScheduler.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/EngagementSyncScheduler.kt)
- Responsibilities:
    - Enqueue `InteractionSyncWorker` using `WorkManager.enqueueUniqueWork`.
    - Use `ExistingWorkPolicy.APPEND_OR_REPLACE` (or `REPLACE` for immediate sync).
    - Configure constraints (Network: CONNECTED).

#### [MODIFY] [EngagementRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/EngagementRepository.kt)
- Inject `EngagementSyncScheduler`.
- In `saveEngagement`, ensure the entity is mapped with `syncState = SyncState.PENDING`.
- Call `syncScheduler.scheduleSync()` after successful Room insertion.
- Add methods for the Worker to fetch pending records and update status.

#### [NEW] [FirestoreEngagementRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/FirestoreEngagementRepository.kt)
- Implement `uploadEngagement(userId: String, evidence: EngagementEvidence): Result<Unit>`.
- Path: `users/{userId}/engagement/{artifactId}`.
- Mapping: `coverage` -> `Blob`, `durationMs` -> `totalDurationMs`, etc.
- **Strict Exclusion**: Do NOT write `isCommentUnlocked`, `unlocked`, or `unlockReason`.

---

### [Component] Background Sync (WorkManager)

#### [MODIFY] [InteractionSyncWorker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/InteractionSyncWorker.kt)
- Inject `EngagementRepository` and `FirestoreEngagementRepository`.
- Implement `syncEngagement(userId: String)`:
    - Fetch pending engagements from `EngagementRepository`.
    - Batch them into a list for processing (allowing for future batch Firestore writes).
    - For each engagement:
        - Update local state to `SYNCING`.
        - Perform Firestore upload.
        - On success: `markAsSynced`.
        - On failure: Update with `FAILED` and error details.

## Verification Plan

### Automated Tests
- **Unit Tests**:
    - `EngagementRepository` logic for setting `PENDING` state.
    - `FirestoreEngagementRepository` mapping (BitSet to Blob).
    - `EngagementSyncScheduler` enqueuing logic (Unique Work).
- **Integration Tests**:
    - Full sweep: `InteractionSyncWorker` processing multiple pending engagements and updating Room correctly.

### Manual Verification
1. Play audio -> Verify Room state is `PENDING`.
2. Observe Worker execution -> Verify Firestore document exists.
3. Verify Room state transitions: `PENDING` -> `SYNCING` -> `SYNCED`.
4. Test offline scenario -> Verify state remains `PENDING` until network restored and Worker runs.
5. Test failure scenario (e.g. simulated network error) -> Verify `FAILED` state and `lastSyncError` populated.
