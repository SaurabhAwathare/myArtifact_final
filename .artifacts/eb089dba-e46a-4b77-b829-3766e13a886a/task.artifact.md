# Task List: Production Database Baseline Cleanup

- [x] Archive/Clear historical migrations in `DatabaseMigrations.kt`
- [x] Remove `QueuedUpload` and `QueuedUploadDao`
- [x] Update `AppDatabase` (remove `QueuedUpload`, keep version 60)
- [x] Clean up `ArtifactDraftEntity` (remove verified dead fields)
- [x] Clean up `ArtifactEntity` (rename Sigil columns, add `commentCount`)
- [x] Clean up `UserLocalEntity` (rename Sigil columns)
- [x] Update `DraftDao` (remove dead methods)
- [x] Update `Converters` (remove `EmotionResult` converters)
- [x] Update `DatabaseMaintenanceManager` (remove `QueuedUpload` pruning)
- [x] Update `DatabaseModule` (remove `QueuedUploadDao` provider)
- [x] Update `ArtifactDao` (consistent Sigil column names in queries)
- [x] Update `DatabaseMaintenanceManagerTest` (remove `QueuedUploadDao` references)
- [ ] Verify build and run tests
- [ ] Verify Room schema export (`60.json`)
