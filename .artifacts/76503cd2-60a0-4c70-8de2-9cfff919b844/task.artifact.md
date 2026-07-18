# Task: Fix Comment Composer Respond Button State Oscillation

- [x] **Phase 1: ViewModel Observer Lifecycle**
    - [x] Add `unlockObservationJob` and cancellation logic to `CommentViewModel`.
    - [x] Implement `COMMENT_TRACE` logging.
- [x] **Phase 2: Repository Flow Purification**
    - [x] Inject `@ApplicationScope` into `EngagementRepository`.
    - [x] Apply `distinctUntilChanged()` to `localFlow`.
    - [x] Move `updateLocalUnlockCache` to `remoteFlow.onEach` and launch in `externalScope`.
- [x] **Phase 3: Database Concurrency Guard**
    - [x] Update `EngagementDao.updateUnlockStatus` query with `remoteUpdatedAt` check.
- [x] **Phase 4: Verification**
    - [x] Run Manual Scenarios (1-5).
    - [x] Verify logs for stability.
    - [ ] Run Manual Scenarios (1-5).
    - [ ] Verify logs for stability.
