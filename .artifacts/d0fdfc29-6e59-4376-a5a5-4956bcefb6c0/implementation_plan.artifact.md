# Implementation Plan - Fix Comment Unlock Synchronization Loop-hole

Fix the propagation and persistence of authoritative backend unlocks to ensure the UI correctly reflects the "Unlocked" state even during active playback.

## User Review Required

> [!IMPORTANT]
> This change introduces "Monotonic Unlock" logic: once an artifact is marked as unlocked in the local database, subsequent playback progress updates cannot regress it back to a locked state.

## Proposed Changes

### [Backend] Authoritative Metadata Advancement

Ensure that every authoritative unlock update from the backend is identifiable as a "new" state by the client.

#### [MODIFY] [index.ts](file:///F:/Android Project/01/functions/src/index.ts)
- Add `"updatedAt": FieldValue.serverTimestamp()` to the engagement update payload in `onEngagementUpdated`.

---

### [Android] Robust Local Synchronization

Update the local database logic to correctly handle authoritative updates and prevent stale local state from overwriting them.

#### [MODIFY] [EngagementDao.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/EngagementDao.kt)
- **Update `updateUnlockStatus`**: Modify the `WHERE` clause to accept updates if the `isCommentUnlocked` state is transitioning from `false` to `true`, even if the timestamp is equal (as a safety measure).
- **Add `saveEngagementMonotonic`**: Implement a `@Transaction` method that merges incoming tracking evidence with existing unlock status, preventing an "Unlocked" status from being overwritten by stale tracker evidence.

#### [MODIFY] [EngagementRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/EngagementRepository.kt)
- Update `saveEngagement` to call the new `saveEngagementMonotonic` method in the DAO.

---

## Verification Plan

### Automated Tests
- **Android**: Update `EngagementRepositoryTest.kt` or create a specific integration test for the monotonic unlock logic.
- **Backend**: No new backend tests required for this metadata change, but verify existing tests pass.

### Manual Verification
- Perform a "Live Unlock" test:
    1. Open an artifact.
    2. Start playback.
    3. Manually trigger a backend unlock (or simulate 95% threshold).
    4. Verify the "Respond" button enables **while playback is still running**.
