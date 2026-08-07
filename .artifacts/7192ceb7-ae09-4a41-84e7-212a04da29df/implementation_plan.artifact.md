# Implementation Plan - Fix Firestore Startup PERMISSION_DENIED

Fixing the Firestore `PERMISSION_DENIED` issue encountered during app startup. This involves initializing Firebase App Check and relaxing Firestore security rules to allow one-time data migration for legacy users.

## User Review Required

> [!IMPORTANT]
> The security rules change allows the `delete` operation on previously "privileged" fields (like `isAdmin`, `email`) for the authenticated owner. This is required for the `UserRepository.getOrCreateProfile` migration logic to function. Since the `getOrCreateProfile` transaction only deletes these fields from the root and moves them to `/private/settings` (which is still locked to the owner), this does not introduce a security regression, but it does change the "immutability" guarantee for these fields at the root level.

## Proposed Changes

### Core Startup

#### [MODIFY] [StartupCoordinator.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/startup/StartupCoordinator.kt)
- Add `initializeAppCheck()` method to configure Firebase App Check.
- Use `DebugAppCheckProviderFactory` when `environmentProvider.isDebug` is true.
- Use `PlayIntegrityAppCheckProviderFactory` for production builds.
- Invoke `initializeAppCheck()` within the `start()` sequence before signaling `StartupComponent.CORE` readiness.

### Firestore Security

#### [MODIFY] [firestore.rules](file:///F:/Android Project/01/firestore.rules)
- Update the `update` rule for `/users/{uid}`.
- Refine the `affectedKeys().hasAny(...)` check to permit deletions (`FieldValue.delete()`) of privileged fields, while still blocking any modification to new non-null values.

## Verification Plan

### Automated Tests
- No new automated tests are being added in this task as it involves infrastructure and security rules.
- Existing `UserRepositoryTest` should be run to ensure migration logic is still being called correctly (though rule enforcement happens server-side).

### Manual Verification Checklist
- [ ] Deploy updated `firestore.rules` to Firebase project.
- [ ] Build and run the app in Debug mode.
- [ ] Verify `StartupCoordinator` logs "App Check Initialized: DEBUG".
- [ ] Authenticate a legacy user (user with email/isAdmin in root).
- [ ] Verify `UserRepository` logs "SENSITIVE_DATA_MIGRATED" and "USER_PROFILE_REPAIRED" (if applicable).
- [ ] Verify root user document no longer contains `email`, `isAdmin`, etc.
- [ ] Verify `users/{uid}/private/settings` contains the migrated data.
