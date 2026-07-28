# Task: Fix Remaining Unit Test Failures

- [x] Fix `RecordingRaceConditionTest` reflection lookup
- [x] Fix `ArtifactRepositoryTest` mocking for Firestore delegation
- [x] Fix `BackupEncryptionManagerCacheTest` to use `deriveKey`
- [x] Stabilize `RecordingRaceConditionTest`
    - [x] Add `cleanupManager` mock
    - [x] Configure `Dispatchers.IO` to use `testDispatcher`
    - [x] Handle Android framework method calls (`stopForeground`, `stopSelf`) in `RecordingService`
    - [x] Mock `Log` and `Looper` as safety for JVM environment
- [ ] Verification
    - [ ] Run `RecordingRaceConditionTest` (2/2 pass)
    - [ ] Run full unit test suite (319/319 pass)
