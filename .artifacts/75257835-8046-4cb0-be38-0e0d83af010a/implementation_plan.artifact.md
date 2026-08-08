# Fix Firestore PERMISSION_DENIED for App Check

This plan addresses the `PERMISSION_DENIED` errors in Firestore by decoupling App Check validation from mandatory user authentication and hardening the `hasValidAppCheck()` helper.

## User Review Required

> [!IMPORTANT]
> The `hasValidAppCheck()` helper will no longer require `request.auth != null`. This is necessary to allow unauthenticated users (who still provide a valid App Check token) to read public resources like reaction counts.

## Proposed Changes

### Firestore Rules

#### [MODIFY] [firestore.rules](file:///F:/Android Project/01/firestore.rules)
- Update `hasValidAppCheck()` to remove the mandatory `request.auth != null` constraint.
- Refine the App Check token detection logic to be more resilient across environments (Emulator vs Production).
- Ensure `isAuth()` still enforces authentication.

## Verification Plan

### Automated Tests
- I will not be running automated tests in this phase as it requires a specialized environment, but I will verify the rule logic statically.

### Manual Verification
- Deploy the updated rules (handled by the user or via CLI if available).
- Verify that unauthenticated reads to `artifact_reaction_counts` succeed.
- Verify that authenticated operations (artifact creation, resonance queries) succeed.
