# Tasks - Fix Comment Unlock Synchronization Loop-hole

- [ ] **Phase 1: Backend Authoritative Metadata**
    - [ ] Update `functions/src/index.ts` to include `updatedAt` in unlock payload.
- [ ] **Phase 2: Android Monotonic Sync**
    - [ ] Update `EngagementDao.kt` with `saveEngagementMonotonic` and improved `updateUnlockStatus` query.
    - [ ] Update `EngagementRepository.kt` to use the monotonic save.
- [ ] **Phase 3: Verification**
    - [ ] Run synchronization integration tests.
    - [ ] Verify unlock propagation during simulated playback.
