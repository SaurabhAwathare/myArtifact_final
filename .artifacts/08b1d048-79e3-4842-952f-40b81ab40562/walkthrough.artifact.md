# Walkthrough - Engagement Instrumentation

I have instrumented the engagement update and sync pipeline to investigate the "Ghost Update" hypothesis. This involves logging the state of local database records during updates and the visibility of pending records in the background worker.

## Changes Made

### 1. Database Layer (DAO)
- Modified [EngagementDao.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/EngagementDao.kt) to change the return type of `updateLastPosition` from `Unit` to `Int`. This allows us to track if any rows were actually modified by the SQL `UPDATE` statement.

### 2. Repository Layer
- Updated [EngagementRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/EngagementRepository.kt) to:
    - Inject `DiagnosticLogger`.
    - In `updateLastPosition`, added logging to capture `ExistsBeforeUpdate` and `RowsUpdated`.
    - Logs are tagged with `INVESTIGATION_LOG` and `Stage=RoomUpdate`.

### 3. Worker Layer
- Updated [InteractionSyncWorker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/InteractionSyncWorker.kt) to:
    - Log the state of the worker's pending queue at the start of `syncEngagement`.
    - Logs are tagged with `INVESTIGATION_LOG`, `Stage=WorkerLoad`, `PendingSyncCount`, and the list of `ArtifactIds`.

## Verification Results

### Automated Tests
- Ran `:app:assembleDebug` to ensure the project compiles with the changed DAO signature and new dependencies. **Status: Success.**

### Manual Verification Instructions
1. Deploy the app to a device or emulator.
2. Open an artifact and play it (or seek) to trigger `updateLastPosition`.
3. Check Logcat for:
   ```text
   INVESTIGATION_LOG | Stage=RoomUpdate | TRACE_ID=<id> | ExistsBeforeUpdate=true | RowsUpdated=1
   ```
4. If `RowsUpdated=0`, it confirms that Room did not find a matching row to update.
5. Trigger a sync (or wait for the worker) and check Logcat for:
   ```text
   INVESTIGATION_LOG | Stage=WorkerLoad | PendingSyncCount=1 | ArtifactIds=[...]
   ```
6. Compare the `ArtifactIds` in the worker log with the `TRACE_ID` from the repository log.

## Next Steps
- Analyze the logs to determine if the issue is a missing local record (Ghost Update) or a failure in the worker's ability to see/process the pending record.
