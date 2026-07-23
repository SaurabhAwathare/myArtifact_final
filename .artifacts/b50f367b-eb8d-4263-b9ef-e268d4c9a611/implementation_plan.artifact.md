# Implementation Plan - PBKDF2 Key Derivation Optimization

Optimize the backup key derivation process by implementing an in-memory cache for the derived `SecretKeySpec`. This addresses the performance bottleneck where the expensive PBKDF2 derivation (600,000 iterations) was executed repeatedly for every draft during backup.

## User Review Required

> [!IMPORTANT]
> The cache is in-memory and will persist for the duration of the process lifetime. Invalidation is critical and has been tied to logout, account deletion, and recovery phrase changes.

## Proposed Changes

### Security Component

#### [MODIFY] [BackupEncryptionManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/security/BackupEncryptionManager.kt)
- Introduce `cachedBackupKey` to store the derived key.
- Implement `keyMutex: Mutex` for thread-safe derivation.
- Update `getBackupKey()` to implement double-checked locking:
    - Return `cachedBackupKey` if not null.
    - Otherwise, acquire `keyMutex`, check again, and perform derivation on `Dispatchers.Default`.
    - Cache the result.
- Update `saveMnemonic()` to call `invalidateCache()` before saving the new mnemonic.
- Add `fun invalidateCache()` to clear `cachedBackupKey`.
- Remove any diagnostic logging or temporary code if present (none found in current state, but will ensure clean state).

### Auth Component

#### [MODIFY] [LogoutCoordinator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/domain/auth/LogoutCoordinator.kt)
- Inject `BackupEncryptionManager`.
- Call `invalidateCache()` during the `performFullCleanup()` sequence (Phase B: Clear Local State). This automatically covers both **Logout** and **Account Deletion** as both use this coordinator.

### Cleanup Task

#### [MODIFY] [SecurityArchitecture.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/security/SecurityArchitecture.kt)
- Ensure no diagnostic logging remains in `deriveBackupKey`.

## Cache Lifecycle Summary

| Event | Action | Call Site |
| :--- | :--- | :--- |
| **Process Startup** | Initialize empty cache | `BackupEncryptionManager` Init |
| **First Backup Call** | PBKDF2 Derivation + Cache Write | `getBackupKey()` |
| **Subsequent Backup Call** | Cache Read (Immediate) | `getBackupKey()` |
| **Recovery Phrase Change** | Cache Invalidation | `saveMnemonic()` |
| **User Logout** | Cache Invalidation | `LogoutCoordinator.performFullCleanup()` |
| **Account Deletion** | Cache Invalidation | `LogoutCoordinator.performFullCleanup()` |
| **Backup Disabled** | Cache Invalidation | `LogoutCoordinator.performFullCleanup()` (Assumed part of session cleanup) |

## Verification Plan

### Automated Tests
- Create `BackupEncryptionManagerCacheTest.kt` to:
    - Verify `getBackupKey` returns the same object/key.
    - Verify `invalidateCache` forces a new key (if possible to track, otherwise verify it's null).
    - Verify thread safety with concurrent calls.

### Manual Verification
- Deploy the app and trigger a multi-draft backup.
- Monitor execution time or add temporary logs to confirm PBKDF2 runs only once.
- Perform a logout and verify that the next backup (after re-login) triggers a new derivation.
- Verify that saving a new mnemonic (if possible via UI) invalidates the cache.
