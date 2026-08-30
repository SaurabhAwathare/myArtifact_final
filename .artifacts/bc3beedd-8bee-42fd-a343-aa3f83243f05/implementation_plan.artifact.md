# Implementation Plan - Cross-Account Media Isolation Hardening

Remediate the newly identified privacy leak where decrypted publishing files are stored in the root `cacheDir` and survive logout, potentially exposing User A's cleartext audio to User B.

## User Review Required

> [!IMPORTANT]
> The fix involves relocating temporary files used during the publishing process. While this improves privacy, it means any *active* upload's temporary file will be moved. Existing temporary files in the root `cacheDir` will be treated as "legacy" and purged during the first logout or startup cleanup.

## Proposed Changes

### 1. File Relocation & Storage Management

#### [MODIFY] [StorageManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/util/StorageManager.kt)
- Update `clearUserStorage()` to include a defensive sweep of the root `cacheDir` for any legacy `decrypted_*.m4a` files.
- Ensure `tempUploadDirectory` is correctly created and returned.

#### [MODIFY] [ArtifactPublishingRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/ArtifactPublishingRepository.kt)
- Change the temporary decryption file location from `context.cacheDir` to `storageManager.tempUploadDirectory`.
- This ensures these files are automatically deleted by the existing `StorageManager` logic during logout.

#### [MODIFY] [ArtifactCleanupManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/audio/ArtifactCleanupManager.kt)
- Update `cleanStaleTempFiles()` to sweep BOTH the root `cacheDir` (for legacy files) and the new `tempUploadDirectory`.

---

### 2. Logout Orchestration

#### [MODIFY] [LogoutCoordinator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/auth/LogoutCoordinator.kt)
- Verify that `UploadService` is stopped in Phase A before `StorageManager.clearUserStorage()` is called in Phase B. This prevents file locks from blocking the deletion of the `upload_temp` directory.

---

### 3. Testing & Verification

#### [NEW] [MediaIsolationIsolationTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/domain/auth/MediaIsolationIsolationTest.kt)
- **Test Case 1**: Verify User A's decrypted file in `upload_temp` is removed on logout.
- **Test Case 2**: Verify legacy `decrypted_*.m4a` files in root `cacheDir` are removed on logout.
- **Test Case 3**: Verify User B cannot access User A's temporary files.
- **Test Case 4**: Verify normal retry path still works (temporary file is preserved if upload fails but user is still logged in).

## Verification Plan

### Automated Tests
- Run the new `MediaIsolationIsolationTest`.
- Run `LogoutCoordinatorTest` to ensure no regressions in the overall logout flow.
- Command: `./gradlew :app:testDebugUnitTest --tests "com.saurabh.artifact.domain.auth.*"`

### Manual Verification
1. Log in as User A.
2. Trigger an upload (e.g., publish an artifact).
3. Stop the app/interrupt the network to ensure a temporary decrypted file exists.
4. Verify the file exists in `cache/upload_temp/`.
5. Log out.
6. Verify the file is GONE.
7. Verify root `cache/` has no `decrypted_*.m4a` files.
