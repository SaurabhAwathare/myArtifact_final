# Tasks - Zombie WorkManager Retry Fix

- [x] Create `AcquisitionResult.kt` enum
- [x] Update `UploadTaskDao.kt` to return `AcquisitionResult`
- [x] Update `PublishingWorker.kt` to handle `AcquisitionResult` and terminate if `MISSING`
- [x] Update `UploadService.kt` to handle `AcquisitionResult`
- [x] Verify build and run basic unit tests
