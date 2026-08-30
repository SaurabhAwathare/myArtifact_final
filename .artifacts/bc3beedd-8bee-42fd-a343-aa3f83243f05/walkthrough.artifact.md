# Media Isolation Hardening Walkthrough

This document summarizes the changes made to harden cross-account media isolation and the results of the manual audit.

## Changes Implemented

### 1. Relocation of Decrypted Publishing Temp Files
- **Relocation**: Moved `decrypted_*.m4a` files from the root `cacheDir` to a dedicated `cache/upload_temp/` directory managed by `StorageManager`.
- **Deterministic Naming**: Files are now named `decrypted_${draftId}.m4a` for reliable tracking.
- **Resumability**: Temp files are preserved during transient failures to support resumable uploads but are purged on success or terminal failure.

### 2. Defensive Cleanup Hardening
- **StorageManager Root Sweep**: Added a defensive sweep in `clearUserStorage()` that targets any legacy `decrypted_*.m4a` files in the root cache directory.
- **ArtifactCleanupManager**: Updated to sweep both the root cache and the new `upload_temp` directory for stale files on app startup. It uses the `UploadTask` status and a 12-hour timeout to distinguish between active uploads and orphans.

### 3. Logout Sequence Optimization
- **MediaCache Release**: Added `MediaCache.release()` as the very first step in Phase A of logout to ensure all file handles are closed.
- **Phase Ordering**: Ensured that all services (`UploadService`, `PlaybackService`, `ExportService`) and `WorkManager` tasks are stopped before Phase B (storage deletion) begins.
- **Deterministic Delays**: Added small delays (200ms-500ms) after stopping services and workers to allow for filesystem unlock/yield.

## Manual Audit Summary

The code was audited against the following isolation invariants:

| Invariant | Verification Result |
| :--- | :--- |
| **No cleartext media remains after logout** | `StorageManager.clearUserStorage()` targets all temp and draft directories, including the new `upload_temp`. |
| **Legacy orphans are handled** | Defensive sweep in `StorageManager` specifically looks for files matching the old naming pattern in the root cache. |
| **Interrupted uploads survive process death** | `ArtifactPublishingRepository` preserves the temp file in its `finally` block if the error was transient. |
| **File handles are released before deletion** | `LogoutCoordinator` releases `MediaCache` and stops services before calling cleanup. |

## Verification Results

### Automated Tests
- **StorageManagerIsolationTest.kt**: (Pending env fix) Designed to verify root sweep and temp dir purging.
- **MediaIsolationIsolationTest.kt**: (Pending env fix) Designed to verify logout phase ordering.
- *Note: Tests could not be executed due to local Gradle environment constraints (AndroidLocationsBuildService errors), but the logic has been cross-referenced with existing system patterns.*

### Build Status
- **Success**: Run `:app:assembleDebug` confirmed that the changes are syntactically correct and integrate with Dagger/Hilt.

## Final Filesystem Structure
```
cache/
  ├── upload_temp/           <-- Target for decrypted publishing files
  │   └── decrypted_*.m4a
  ├── media_cache/           <-- Released before deletion
  └── ... (other whitelisted caches)
```
