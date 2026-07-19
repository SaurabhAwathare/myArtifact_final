# Walkthrough - Firestore Security Model Refactoring (Phase 1)

I have successfully implemented Phase 1 of the Firestore security model refactoring for `users/{uid}`. This phase focuses on moving clearly sensitive data to a protected sub-collection and preparing for a simplified security rule set.

## Changes Made

### 1. Atomic Lazy Migration
Implemented an idempotent, atomic migration within `UserRepository.getOrCreateProfile`. When a user logs in, the app checks if sensitive fields exist at the root document. If found, they are moved to `private/settings` and deleted from the root in a single transaction.
- **Affected Fields**: `email`, `realName`, `fcmToken`, `isAdmin`, `accountStatus`, `admin`.
- **Reference**: [UserRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/UserRepository.kt)

### 2. Firestore Security Rules (Release A)
Updated `firestore.rules` to support the migration. The rules now explicitly allow the document owner to delete these sensitive fields from their root document, while still preventing them from being modified to new values.
- **Reference**: [firestore.rules](file:///F:/Android Project/01/firestore.rules)

### 3. FCM Token Path Update
Fixed `FCMService.kt` to write new FCM tokens directly to `users/{uid}/private/settings` instead of the root document.
- **Reference**: [FCMService.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/service/FCMService.kt)

### 4. Data Model Documentation
Updated `User.kt` documentation to reflect the new architecture and clearly state that sensitive fields have moved.
- **Reference**: [User.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/model/User.kt)

## Verification Results

### Automated Tests
- **Security Rules**: Updated `admin_rules.test.js` to verify that owners can delete sensitive fields from root (supporting Release A migration).
- **Migration Logic**: Created `UserRepositoryMigrationTest.kt` to verify that the transaction correctly identifies fields at root, moves them to private, and deletes the originals.

### Manual Verification Steps (Recommended)
1. **Legacy User Test**:
   - Manually add an `email` field to a root user document in the Firestore Emulator.
   - Launch the app and log in as that user.
   - Observe the `SENSITIVE_DATA_MIGRATED` log and verify the field moved to `private/settings`.
2. **Idempotency**:
   - Log in a second time and verify no further migration transactions are attempted for that user.

## Next Steps (Release B)
Once production telemetry confirms that the vast majority of active users have migrated, proceed with **Release B**:
- Simplify `users/{uid}` read rules to: `allow read: if isAuth();`
- Remove the deprecated field deletion allowance from the root document.
