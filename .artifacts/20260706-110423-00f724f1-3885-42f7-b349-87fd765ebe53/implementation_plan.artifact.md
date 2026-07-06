# Minimal Runtime Verification Plan - Emergency Identity Reset

Perform a high-efficiency runtime verification to validate production-critical behaviors.

## Scenarios

### 1. Emergency Identity Reset
- **Action**: Launch -> Sign in -> Profile -> Edit Identity -> Click "Protect" -> "Randomize Now".
- **Verification**: UI updates (name/avatar), success message shown, `IdentitySyncWorker` scheduled in Logcat.

### 2. Recovery
- **Action**: Force-stop immediately after reset trigger. Restart app.
- **Verification**: `UserProfileManager` detects pending sync, recovery worker scheduled/maintained, no duplicates.

### 3. Final State
- **Verification**: Propagation completes, `identityResetVersion == lastCompletedIdentityVersion`, no exceptions.

## Logging Filters
- `UserProfileManager`
- `IdentitySyncWorker`
- `WorkManager`
- `EmergencyIdentityReset`
