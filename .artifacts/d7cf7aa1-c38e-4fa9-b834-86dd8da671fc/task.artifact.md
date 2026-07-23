# Task List - Fix Race Condition (Finding #2)

- [x] Update `EngagementDao.markAsSynced` with state guard and return type `Int`
- [x] Update `EngagementRepository.markEngagementSynced` to return `Int`
- [x] Update `InteractionSyncWorker` to log when `markAsSynced` affects 0 rows (Enhanced context)
- [x] Create `EngagementSyncRaceTest.kt` with expanded scenarios:
    - [x] Scenario 1: Normal Sync
    - [x] Scenario 2: Race Condition (Update during upload)
    - [x] Scenario 3: Multiple updates during upload
    - [x] Scenario 4: Recovery in next sync cycle
- [!] Run regression tests (Attempted; blocked by environment `AndroidLocationsBuildService` issue)
- [!] Run full `:app:testDebugUnitTest` suite (Attempted; blocked by same issue)
- [ ] Final Verification (Manual playback & sync scenario - Requires active device)
- [x] Update Walkthrough with implementation details and verification status
