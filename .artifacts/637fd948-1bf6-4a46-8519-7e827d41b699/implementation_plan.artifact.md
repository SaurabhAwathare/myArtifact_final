# Secure Data Export Implementation Plan

## Goal
Implement a secure, encrypted data export format (`.artx`) to ensure that user data remains private when exported from the device. This replaces the current unencrypted ZIP export in `DataExportManager`.

## User Review Required
> [!IMPORTANT]
> This implementation separates the **Backup** and **Export** cryptographic domains. While both may originate from the same Recovery Phrase, they will use distinct key derivation paths to ensure domain isolation.

## Proposed Changes

### 1. Cryptographic Domain Separation
We will implement a key derivation strategy that isolates exports from backups.

#### [MODIFY] [SecurityArchitecture.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/security/SecurityArchitecture.kt)
- Add `deriveKey(passphrase: String, salt: ByteArray, context: String): ByteArray`.
- Implement HKDF-based sub-key derivation or use distinct PBKDF2 salts for different contexts.
- Add configuration for `StreamingAead` specifically tuned for export portability (e.g., `AES128_GCM_HKDF_1MB` or similar to handle large audio files efficiently).

### 2. Export Logic Enhancement
#### [MODIFY] [DataExportManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/security/DataExportManager.kt)
- Rename export extension to `.artx`.
- Use `StreamingAead` to encrypt the entire ZIP stream.
- Incorporate Associated Data (AAD) containing:
    - Format Version (e.g., `artifact_export_v1`)
    - Device ID (optional, for metadata)
- Ensure streaming implementation to handle large audio files without memory pressure.

### 3. Key Management
#### [MODIFY] [BackupEncryptionManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/security/BackupEncryptionManager.kt)
- Expose a method to provide the raw recovery phrase (securely) to authorized components for sub-key derivation, or provide a generic `deriveSubKey(context: String)` method.

---

## Verification Plan

### Automated Tests
- **Crypto Domain Isolation Test**: Verify that the key derived for `context="backup"` is different from `context="export"`.
- **Large File Streaming Test**: Mock a 100MB+ audio file and verify export/import succeeds without `OutOfMemoryError`.
- **Integrity Test**: Verify that modifying a single byte of the `.artx` file causes Tink to reject the entire stream during decryption.
- **Wrong Key Test**: Attempt to decrypt an export with a different recovery phrase and verify it fails cleanly.

### Manual Verification
- **Export Flow**: Perform a full export and verify the resulting `.artx` file cannot be opened by standard ZIP tools.
- **Interruption Handling**: Kill the app during a large export and verify the partial file is treated as invalid/corrupted on next attempt.
- **UI Feedback**: Ensure the user is notified if an export fails due to encryption errors.

## Open Questions
- Should we include a "Signature" or "Magic Bytes" at the start of the `.artx` file for easier file type identification before attempting decryption?
- Should the export be bound to the specific user ID in the AAD to prevent cross-account importing? (Recommended: Yes).
