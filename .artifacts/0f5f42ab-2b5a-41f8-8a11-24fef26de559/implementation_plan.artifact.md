# Implementation Plan - Firestore Security Model Refactoring (Phase 1)

Refactor the `users/{uid}` collection to separate public profile information from sensitive account data. This plan follows a phased approach to minimize regression risk and ensures data integrity through atomic transactions and a staged rollout.

## Phase 1: Core Privacy & Security Refactoring

### Goal
Move clearly sensitive data (`email`, `realName`, `fcmToken`, `isAdmin`, `accountStatus`) to `users/{uid}/private/settings` and prepare for simplified Firestore rules.

## User Review Required

> [!IMPORTANT]
> - **Atomic Migration**: Migration will be handled within a single Firestore transaction to ensure data consistency.
> - **Release A (Migration Logic)**: Deploy the logic to move fields naturally as users log in. Rules will be updated to *allow* deletion of sensitive fields from the root by the owner.
> - **Release B (Rule Simplification)**: Once production verification confirms migration progress, simplify `users/{uid}` rules to be globally readable by authenticated users.
> - **Idempotency**: The migration logic will check for the existence of fields before attempting move, ensuring it only runs once per user.

## Proposed Changes

### [Data Model]

#### [MODIFY] [User.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/model/User.kt)
- Ensure `User` class documentation reflects that `email`, `realName`, `fcmToken`, `isAdmin`, and `accountStatus` are deprecated at the root and moved to `private/settings`.

---

### [Firestore Security Rules]

#### [MODIFY] [firestore.rules](file:///F:/Android Project/01/firestore.rules)
- **Release A Update**:
    - Modify `users/{uid}` update rules to allow the owner to `delete` sensitive fields (`email`, `realName`, `fcmToken`, `isAdmin`, `accountStatus`) from the root document.
    - This is required for the migration transaction to succeed.

---

### [Repositories & Services]

#### [MODIFY] [UserRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/UserRepository.kt)
- **Atomic Lazy Migration in `getOrCreateProfile`**:
    - Within the existing `runTransaction` block:
        1. Check if `email`, `realName`, `fcmToken`, `isAdmin`, or `accountStatus` exist in the root `userRef`.
        2. If any exist:
            - Write them into the `privateRef` (`private/settings`).
            - Use `FieldValue.delete()` on those fields in the `userRef`.
    - This ensures the move is atomic and idempotent.

#### [MODIFY] [FCMService.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/service/FCMService.kt)
- Update `updateTokenInFirestore` to write `fcmToken` to `users/{uid}/private/settings` using `merge: true`.

#### [MODIFY] [SettingsRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/SettingsRepository.kt)
- Verify `syncToRemote` usage to ensure `UserSettings` (local preferences) and `UserPrivateSettings` (account data) co-exist safely in `private/settings`.

---

## Verification Plan

### Automated Tests
- Update `admin_rules.test.js` to verify that owners can now delete sensitive fields from root (Release A requirement).
- Add integration tests for `UserRepository.getOrCreateProfile` covering:
    - Migration of all 5 fields.
    - Idempotency (running twice does nothing the second time).
    - Failure safety (if transaction fails, root data remains).

### Manual Verification
1.  **Legacy User Test**:
    - Manually inject `email`, `realName`, and `fcmToken` into a root user document in Emulator.
    - Log in and verify they move to `private/settings` and are removed from root.
    - Log in again and verify no further changes/logs occur.
2.  **FCM Update**: Trigger token refresh and verify it lands in `private/settings`.
