# Implementation Plan - Phase 4: Android Integration (Authoritative Comment Unlock)

Integrate the Android application with the backend comment unlock pipeline by observing the authoritative unlock state from Firestore.

## User Review Required

> [!IMPORTANT]
> This phase is **CLIENT INTEGRATION ONLY**. We will not modify backend code, Cloud Functions, or Firestore Security Rules.

## Proposed Changes

### Domain Layer

#### [NEW] [EngagementState.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/review/EngagementState.kt)
Define a typed enum or sealed class for the backend-provided state (e.g., `LOCKED`, `UNLOCKED`).

#### [NEW] [UnlockStatus.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/review/UnlockStatus.kt)
Domain model for the authoritative state:
- `isCommentUnlocked: Boolean`
- `unlockTimestamp: Long?`
- `engagementState: EngagementState`
- `unlockReason: String?`

#### [MODIFY] [EngagementEvidence.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/review/EngagementEvidence.kt)
Include `unlockStatus: UnlockStatus` to unify local tracking.

### Data Layer (Local)

#### [MODIFY] [ArtifactEngagement.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/ArtifactEngagement.kt)
Add fields for `UnlockStatus` for local caching and offline support.

#### [MODIFY] [EngagementDao.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/EngagementDao.kt)
Update Room entity mapping.

### Data Layer (Repository)

#### [MODIFY] [FirestoreEngagementRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/FirestoreEngagementRepository.kt)
Add `observeRemoteUnlockStatus(userId: String, artifactId: String): Flow<UnlockStatus?>` using a Firestore snapshot listener.

#### [MODIFY] [EngagementRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/EngagementRepository.kt)
- Expose `observeEngagement(artifactId: String): Flow<EngagementEvidence?>` which combines local Room data and remote Firestore data.
- Ensure `syncState` is accurately reflected from the local database.

### UI Layer (Presentation)

#### [NEW] [CommentUnlockState.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/CommentUnlockState.kt)
Define the presentation-layer state machine:
- `LOCKED`
- `SYNCING`
- `VERIFYING` (Genuinely waiting for backend after sync)
- `UNLOCKED`
- `ERROR`

#### [MODIFY] [CommentUiState.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/CommentUiState.kt)
Add `unlockState: CommentUnlockState`.

#### [MODIFY] [CommentViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/CommentViewModel.kt)
- Derive `CommentUnlockState` by combining `EngagementEvidence` (remote status) and local `SyncState`.
- **Logic**:
    - If `remote.isCommentUnlocked == true` -> `UNLOCKED`.
    - Else if `local.syncState == PENDING/SYNCING` -> `SYNCING`.
    - Else if `local.syncState == SYNCED` AND `remote.isCommentUnlocked == false` -> `VERIFYING` (while waiting for the backend snapshot to update).
    - Otherwise -> `LOCKED`.

#### [MODIFY] [CommentComposer.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/components/CommentComposer.kt)
Update the UI to reflect `unlockState`:
- Disable composer if not `UNLOCKED`.
- Show appropriate guidance (e.g., "Listening required", "Verifying listening progress...").

## Verification Plan

### Automated Tests
- Unit tests for `CommentViewModel` to verify the state derivation logic.

### Manual Verification
1.  **Locked user**: Open comments on a new artifact. Verify composer is disabled and "LOCKED" message shown.
2.  **Listening completes**: Finish listening. Verify state changes to `SYNCING`.
3.  **Backend validates**: Once sync is done, verify state changes to `VERIFYING` then `UNLOCKED`.
4.  **Offline listening**: Listen while offline. Verify state remains `SYNCING` or `LOCKED`.
5.  **Connectivity restored**: Reconnect. Verify automatic sync and eventually `UNLOCKED`.
6.  **Backend rejects**: Verify it stays `LOCKED` if threshold not met.
7.  **Process Recreation**: Start sync, kill app, reopen. Verify state restoration without redundant sync.
