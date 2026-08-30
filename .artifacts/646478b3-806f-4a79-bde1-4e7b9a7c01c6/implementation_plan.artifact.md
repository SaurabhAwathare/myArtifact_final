# Implementation Plan - Cross-Account Media Cache Isolation

This plan remediates the privacy defect where media cache data survives logout and leaks to subsequent users. We will ensure all `SimpleCache` file handles are released before the physical storage is purged.

## Proposed Changes

### Domain Layer (Auth Boundary)

#### [MODIFY] [LogoutCoordinator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/auth/LogoutCoordinator.kt)
- **Phase A Refinement**: Move `com.saurabh.artifact.audio.MediaCache.release()` from Phase D to Phase A.
- **Rationale**: Releasing the cache in Phase A ensures that file locks are gone before Phase B attempts to delete the `media_cache` directory.
- **Verification of Purge**: Ensure the `mediaCacheSuccess` flag in the `CleanupResult` accurately reflects whether the release and subsequent physical deletion (in Phase B) succeeded.

## Verification Plan

### Automated Tests
- **Logout Sequence Verification**: Create or update a test in `LogoutCoordinatorTest` to verify that `MediaCache.release()` is called *before* `storageManager.clearUserStorage()`.
- **Physical Isolation Test**:
    1.  Initialize `MediaCache` and add dummy data.
    2.  Execute `logoutCoordinator.performFullCleanup()`.
    3.  Verify that the `media_cache` directory in `cacheDir` no longer exists.
    4.  Verify that re-initializing `MediaCache` results in an empty cache.

### Manual Verification
- **A/B User Isolation**:
    1.  Log in as User A, play a unique audio file (verify it's cached).
    2.  Log out.
    3.  Log in as User B.
    4.  Verify (via logs or `adb shell`) that User A's cached data is physically gone and not serveable to User B.
