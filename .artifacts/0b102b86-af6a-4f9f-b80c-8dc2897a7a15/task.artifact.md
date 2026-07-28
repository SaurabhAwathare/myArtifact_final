# Sigil Migration Cleanup - Task List

- `[x]` Update `ProfileRepairService.kt` to detect legacy fields for cleanup.
- `[x]` Update `UserRepository.kt` to implement idempotent transactional cleanup.
- `[x]` Expand `ProfileRepairServiceTest.kt` with comprehensive test cases.
- `[x]` Verify User Profile cleanup via unit tests.
- `[x]` Summarize work in a walkthrough.
- `[x]` Rename `AvatarConfig.kt` to `SigilConfig.kt`.
- `[x]` Rename `com.saurabh.artifact.model.avatar` to `com.saurabh.artifact.model.sigil`.
- `[x]` Delete obsolete `AvatarParts.kt`.
- `[x]` Rename `com.saurabh.artifact.ui.avatar` to `com.saurabh.artifact.ui.sigil`.
- `[x]` Update Media3 bundle keys in `PlaybackService` and `PlaybackSessionManager`.
- `[x]` Update legacy comments in `ImmersivePlayerScreen.kt` and `ProfileHeader.kt`.
- `[x]` Verify Phase 1 cleanup with a successful build and test pass.
- `[x]` Execute Dry Run and verify results.
- `[x]` Execute Production Migration.
- `[x]` Perform Post-Migration Audit (35 docs).
- `[x]` Produce Final Production Migration Report.
