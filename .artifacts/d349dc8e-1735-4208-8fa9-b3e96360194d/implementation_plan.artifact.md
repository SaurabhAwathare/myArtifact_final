# Implementation Plan - PBKDF2 Caching Optimization Verification

This plan outlines the final end-to-end verification of the PBKDF2 caching optimization in the backup system. We will use diagnostic logging to verify that the key derivation occurs exactly once and is correctly cached/invalidated.

## User Review Required

> [!IMPORTANT]
> This plan involves adding temporary diagnostic logging to the production code to verify the optimization. These logs will be removed once verification is complete.
> The verification requires a physical device or a high-fidelity emulator to observe PBKDF2 performance accurately.

## Proposed Changes

### [Component] Security Diagnostics

We will add temporary logging to `BackupEncryptionManager` to track cache hits and misses, and to `SecurityArchitecture` to track the PBKDF2 derivation process.

#### [MODIFY] [BackupEncryptionManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/security/BackupEncryptionManager.kt)
- Inject `DiagnosticLogger`.
- Log "BACKUP_KEY_CACHE_HIT" when the cached key is returned.
- Log "BACKUP_KEY_CACHE_MISS" when derivation is required.
- Log "BACKUP_KEY_CACHE_INVALIDATED" when `invalidateCache` is called.

#### [MODIFY] [SecurityArchitecture.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/security/SecurityArchitecture.kt)
- Log "PBKDF2_DERIVATION_STARTED" inside `deriveBackupKey`.
- Log "PBKDF2_DERIVATION_COMPLETED" with duration.

## Verification Plan

### Automated Tests
- N/A (Manual verification on device is required for performance and lifecycle observations).

### Manual Verification
1. **Deploy App**: Build and install the app on a physical device.
2. **Initial Backup**:
   - Ensure several drafts are ready for backup.
   - Trigger a backup (e.g., via `BackupSyncWorker`).
   - **Expectation**: "BACKUP_KEY_CACHE_MISS" followed by exactly one "PBKDF2_DERIVATION_STARTED". Subsequent calls in the same session should show "BACKUP_KEY_CACHE_HIT".
3. **Logout & Login**:
   - Perform a logout.
   - **Expectation**: "BACKUP_KEY_CACHE_INVALIDATED" should be logged if the logout flow calls it.
   - Log back in.
   - Perform another backup.
   - **Expectation**: Exactly one new "PBKDF2_DERIVATION_STARTED" occurs.
4. **Update Recovery Phrase**:
   - Change or update the recovery phrase in settings.
   - **Expectation**: "BACKUP_KEY_CACHE_INVALIDATED" followed by exactly one new "PBKDF2_DERIVATION_STARTED" on the next backup.
5. **System Stability**:
   - Confirm no ANRs (Application Not Responding) occur during PBKDF2 (it should be off-main-thread).
   - Verify that existing backups can still be read/decrypted (no regression in key derivation).

## Cleanup
- Remove all added `DiagnosticLogger` calls and injections after verification.
