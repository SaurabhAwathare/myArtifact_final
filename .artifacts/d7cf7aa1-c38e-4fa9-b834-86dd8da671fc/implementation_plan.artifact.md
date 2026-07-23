# Implementation Plan - Fix Race Condition in Finding #2 (InteractionSyncWorker vs ReviewAuthorityService)

## Goal Description
Implement a state guard for the engagement synchronization process to prevent a race condition where newer local updates (set to `PENDING`) are overwritten by a trailing `SYNCED` update from an in-flight worker.

## User Review Required
> [!IMPORTANT]
> The fix utilizes optimistic concurrency by adding a `WHERE syncState = 'SYNCING'` clause to the `markAsSynced` DAO method. This is the smallest safe change that resolves the identified race condition without altering the core sync architecture.

## Proposed Changes

### Database Component

#### [MODIFY] [EngagementDao.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/EngagementDao.kt)
- Update the SQL query for `markAsSynced` to include a state check:
  ```sql
  UPDATE artifact_engagement
  SET syncState = 'SYNCED', lastSyncSuccess = :timestamp, syncRetryCount = 0, lastSyncError = null
  WHERE artifactId = :artifactId AND syncState = 'SYNCING'
  ```

## Verification Plan

### Automated Tests
- **New Regression Test**: `EngagementSyncRaceTest.kt` will be created to explicitly simulate the race condition:
  1. Insert an engagement with `PENDING` state.
  2. Simulate worker transition to `SYNCING`.
  3. Simulate user activity (via `EngagementRepository.updateLastPosition`) transitioning the state back to `PENDING`.
  4. Call `markAsSynced` (simulating worker completion).
  5. **Assertion**: Verify the state remains `PENDING`, ensuring it will be re-synced in the next cycle.
- **Full Suite**: Run `:app:testDebugUnitTest` to ensure no regressions in existing sync logic.

### Manual Verification
- Review Logcat for `INVESTIGATION_LOG` entries during test execution to confirm the state transitions are handled as expected.
