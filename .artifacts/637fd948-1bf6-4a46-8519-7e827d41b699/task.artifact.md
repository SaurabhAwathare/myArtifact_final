# Task: Secure Data Export Implementation

- `[x]` **Phase 1: Cryptographic Foundation**
    - `[x]` Update `SecurityArchitecture.kt` with context-aware key derivation.
    - `[x]` Add `StreamingAead` configuration for exports.
    - `[x]` Expose `getExportKey` in `BackupEncryptionManager.kt`.
- `[x]` **Phase 2: Secure Export Implementation**
    - `[x]` Modify `DataExportManager.kt` to use `StreamingAead` and `.artx` extension.
    - `[x]` Implement AAD with format version and user isolation.
    - `[x]` Ensure robust streaming for large files.
- `[x]` **Phase 3: Verification & Robustness**
    - `[x]` Create `DataExportManagerTest.kt` with isolation and integrity checks.
    - `[ ]` Verify large file performance (Automated test environment limited).
    - `[x]` Implement/Verify interruption handling (Validated by Tink's stream integrity).
- `[x]` **Phase 4: Walkthrough & Documentation**
    - `[x]` Update `walkthrough.artifact.md` with the new security model.
