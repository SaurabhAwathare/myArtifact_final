# Investigation Plan: Google Sign-In Failure (Revised)

This plan focuses on tracing the authentication flow to determine exactly where the failure occurs and verifying the device's environment.

## User Review Required

> [!IMPORTANT]
> The investigation has shifted from configuration hypothesis to **runtime tracing**. We have confirmed that the UI displays "Your presence is unknown. Please reveal yourself.", which maps to a `NoCredentialException` in the `CredentialManager` flow.

> [!WARNING]
> The request **never reaches Firebase** (`FirebaseAuth.signInWithCredential()`) because it fails earlier in the `CredentialManager` get-token phase.

## Proposed Steps

### 1. Trace the Authentication Flow
- **Verify Entry**: Confirm the "Continue with Google" button click initiates `getGoogleCredential`.
- **Exit Point**: Determine why `CredentialManager` returns `NoCredentialException`.
  - Is it due to no Google accounts on the device?
  - Is it due to the `SecurityException` observed in GMS logs preventing the provider from functioning?
- **Firebase Reachability**: Confirm that `signInWithGoogle` in `LoginViewModel` is **not** being called.

### 2. Verify Connectivity
- **DNS Resolution**: Confirm the `Unable to resolve host` error persists and investigate if it's a general emulator network issue.
- **Service Reachability**: Attempt to verify if Google and Firebase services are reachable from the device shell/environment.

### 3. Environment Assessment
- **GMS Stability**: Link the `SecurityException: Unknown calling package name 'com.google.android.gms'` to the `CredentialManager` failure.
- **Account Status**: Verify if a Google account is properly registered on the test device.

## Verification Plan

### Manual Verification
- Review logcat specifically for `CredManProvService` and `GoogleApiManager` interactions during the button click.
- Check for any "Generic error" or specific Auth category logs that were previously missed.

### Automated Tests
- None required.
