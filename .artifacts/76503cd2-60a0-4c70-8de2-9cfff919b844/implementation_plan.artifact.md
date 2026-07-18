# Implementation Plan – Fix Comment Composer Respond Button State Oscillation

Fix the "Respond" button continuously enabling/disabling due to competing unlock state updates by eliminating observer leaks, removing repository feedback loops, and stabilizing state propagation.

## User Review Required

> [!IMPORTANT]
> The fix involves introducing `@ApplicationScope` into `EngagementRepository` to handle background persistence of remote unlock status. This ensures that the flow remains pure and doesn't trigger its own emissions.

> [!NOTE]
> Based on the review, I have **removed** the proposal to include `remoteUpdatedAt` in `ArtifactEngagement.equals()`. Keeping it out of equality is actually the key to breaking the feedback loop, as metadata-only updates to Room will no longer trigger the `distinctUntilChanged` collector.

## Proposed Changes

### [Comment UI Layer]

#### [MODIFY] [CommentViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/comment/CommentViewModel.kt)
- Introduce a private `unlockObservationJob: Job?` to track the active observation.
- Cancel the existing job in `observeUnlockStatus()` before launching a new one to prevent multiple collectors.
- Add `COMMENT_TRACE` logs for observation lifecycle (Start/Cancel/Update).
- Add logging for `unlockState` and `isSubmitting` changes to verify stability.

### [Data & Repository Layer]

#### [MODIFY] [EngagementRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/EngagementRepository.kt)
- Inject `@ApplicationScope private val externalScope: CoroutineScope` to handle background updates.
- Apply `distinctUntilChanged()` to the `localFlow` in `observeEngagementEvidence()`.
- Move `updateLocalUnlockCache()` call out of the `combine` block.
- Instead, use `onEach` on the `remoteFlow` to trigger the side-effect write.
- Launch the `updateLocalUnlockCache()` call in `externalScope`.
- Ensure `combine` remains a pure transformation.

#### [MODIFY] [EngagementDao.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/EngagementDao.kt)
- Update `updateUnlockStatus` query to include a concurrency check: `WHERE artifactId = :artifactId AND (remoteUpdatedAt IS NULL OR remoteUpdatedAt < :remoteUpdated)`. This prevents stale writes from overwriting newer backend states.

#### [MODIFY] [ArtifactEngagement.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/data/local/ArtifactEngagement.kt)
- Explicitly ensure `remoteUpdatedAt` is **EXCLUDED** from `equals()` and `hashCode()` (it currently is, so I will maintain this invariant). This breaks the loop when Room is updated with new sync metadata.

## Verification Plan

### Automated Tests
- I will verify the build and check for any obvious regressions in the modified files.
- Unit tests for `CommentViewModel` (if they exist) should be run.

### Manual Verification
1. **Scenario 1: Observer Leak**
   - Open Comment Sheet for Artifact A.
   - Navigate to Artifact B.
   - Verify (via logs) that Artifact A's observation is cancelled and only Artifact B is observed.
2. **Scenario 2: State Stability**
   - Open Comment Sheet.
   - Observe "Respond" button state while typing and after sync.
   - Verify no flickering or repeated enabling/disabling.
3. **Scenario 3: Offline Persistence**
   - Open Artifact, let it unlock.
   - Go offline.
   - Re-open Artifact.
   - Verify comments remain unlocked (local cache working).
4. **Scenario 4: Rapid Navigation**
   - Navigate A -> B -> C -> A -> D rapidly.
   - Verify only one active `COMMENT_TRACE` collector exists at any time.
5. **Scenario 5: Rotation / Recreation**
   - Rotate device while the comment sheet is open.
   - Verify old observation is cancelled and exactly one new observation starts.
