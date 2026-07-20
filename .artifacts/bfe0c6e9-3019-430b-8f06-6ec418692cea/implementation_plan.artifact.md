# Engagement Synchronization Verification Plan

## Goal Description
Verify that listener engagement evidence (coverage, duration, positions) is reliably synchronized from the local Room database to Cloud Firestore and that the authoritative unlock status from the backend is correctly reconciled in the UI.

## User Review Required
> [!IMPORTANT]
> This verification assumes that the Cloud Function for processing engagement is already deployed and functional on the backend. If the backend is not processing the `engagement` document, the UI will stay in the `VERIFYING` state (or `LOCKED`).

## Execution Path
1.  **Evidence Generation**: `ReviewAuthorityService` monitors playback and updates `EngagementEvidence` via `ReviewTracker`.
2.  **Room Persistence**: `EngagementRepository` saves evidence to `artifact_engagement` table with `syncState = PENDING`.
3.  **Sync Trigger**: `EngagementSyncScheduler` enqueues `InteractionSyncWorker`.
4.  **Firestore Upload**: `InteractionSyncWorker` uploads payload to `users/{userId}/engagement/{artifactId}`.
5.  **Reconciliation**: `EngagementRepository.observeEngagementEvidence` combines local Room state with Firestore `addSnapshotListener`.
6.  **UI Feedback**: `CommentViewModel` derives `CommentUnlockState` (LOCKED -> SYNCING -> VERIFYING -> UNLOCKED).

## Proposed Verification Steps

### 1. [ReviewAuthorityService] Evidence Reliability
- **Verify**: Check `ReviewAuthorityService` periodic persistence (5s interval).
- **Files**: [ReviewAuthorityService.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/ReviewAuthorityService.kt)

### 2. [Room] Persistence & State
- **Verify**: `EngagementDao` correctly sets `PENDING` state and updates `lastPositionMs`.
- **Files**: [EngagementDao.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/EngagementDao.kt), [EngagementRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/EngagementRepository.kt)

### 3. [WorkManager] Sync Pipeline
- **Verify**: `InteractionSyncWorker` handles transient vs permanent failures. Check `MAX_RETRIES` and status updates.
- **Files**: [InteractionSyncWorker.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/worker/InteractionSyncWorker.kt)

### 4. [Firestore] Authoritative Merge
- **Verify**: `FirestoreEngagementRepository` uses `SetOptions.merge()` to avoid stomping on `isCommentUnlocked`.
- **Files**: [FirestoreEngagementRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/FirestoreEngagementRepository.kt)

### 5. [UI] Unlock State Reconciliation
- **Verify**: `CommentViewModel` handles the transition from `SYNCED` to `UNLOCKED` via the `VERIFYING` state.
- **Files**: [CommentViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/CommentViewModel.kt)

## Verification Plan

### Automated Tests
- I will search for existing tests for these components.
- If none exist, I will propose creating a unit test for `CommentViewModel` to verify `deriveUnlockState` logic.

### Manual Verification
1.  **Log Analysis**: Deploy the app and monitor Logcat for `INVESTIGATION_LOG` and `COMMENT_TRACE` tags.
2.  **Database Inspection**: Use App Inspection in Android Studio to verify `artifact_engagement` table updates.
3.  **Firestore Inspection**: Verify document structure at `users/{uid}/engagement/{artifactId}` in Firebase Console.
