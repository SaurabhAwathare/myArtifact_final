# Walkthrough: Secure Data Export (.artx)

The data export feature has been overhauled to provide industry-standard encryption, ensuring user privacy is maintained even when data is moved off-device.

## Changes Made

### 1. Cryptographic Domain Separation
We now ensure that the keys used for Cloud Backup and Local Export are mathematically distinct, even though they both originate from the user's Recovery Phrase.
- **Backup Salt**: `artifact_backup_v1_salt`
- **Export Salt**: `artifact_export_v1_salt`
This isolation ensures that a compromise or format change in one domain does not affect the other.

### 2. Encrypted Export Format (.artx)
The "Export All" feature now produces an encrypted `.artx` file instead of a plain ZIP.
- **Algorithm**: Tink's `StreamingAead` (AES256-GCM-HKDF).
- **Chunking**: Data is encrypted in 4KB segments, allowing for low-memory streaming of large audio files.
- **AAD (Associated Data)**: Each export is cryptographically bound to the format version and the specific User ID:
  `artifact_export_v1:<userId>`
  This prevents "Cross-Account" importing where an export from User A is imported into User B's account.

### 3. Implementation Details

#### [DataExportManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/security/DataExportManager.kt)
The core export logic now wraps the `ZipOutputStream` with an encrypting stream:
```kotlin
streamingAead.newEncryptingStream(rawOutputStream, aad).use { encryptedStream ->
    ZipOutputStream(encryptedStream).use { zipOut ->
        // ... add entries ...
    }
}
```

#### [BackupEncryptionManager.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/security/BackupEncryptionManager.kt)
Added a new derivation path for the export key:
```kotlin
suspend fun getExportKey(): SecretKeySpec {
    // ... derive using exportSalt ...
}
```

## Verification Results

### Automated Tests
- Created [DataExportManagerTest.kt](file:///F:/Android Project/01/app/src/test/java/com/saurabh/artifact/security/DataExportManagerTest.kt).
- **Domain Separation**: Verified that `deriveKey` produces different outputs for backup and export salts.
- **Encryption Integrity**: Verified that the exported file is not a valid ZIP and requires the correct key and AAD to decrypt.
- **AAD Mismatch**: Verified that decryption fails if the User ID in the AAD does not match the one used during export.

### Manual Verification
- Verified (via code analysis) that `copyTo` is used for audio files, ensuring that files of any size are streamed through the encryption layer without loading the entire content into memory.
- Verified that Tink's streaming implementation provides atomic-like guarantees; an interrupted export will fail the HMAC check on decryption, preventing partial/corrupted data from being imported.

> [!TIP]
> Future "Import" functionality should use the same AAD structure and `encryptionManager.getExportKey()` to safely restore user data.
