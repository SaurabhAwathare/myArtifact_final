# Task: Phase 2.2 Eliminate Remaining Legacy Cleanup Paths

- [ ] Refactor `ArtifactRepository` to remove direct local sync/deletion logic
- [ ] Refactor `DraftDeletionManager` to remove hard-delete and lifecycle logic
- [ ] Update `RecordingRepository` to delegate all cleanups to `ArtifactCleanupManager`
- [ ] Update `RecordingService` to use the new cleanup pipeline for canceled recordings
- [ ] Remove legacy `DeletionWorker` and its references
- [ ] Verify `CleanupWorker` is the unique owner of Room record deletion
- [ ] Perform final repository-wide static audit
