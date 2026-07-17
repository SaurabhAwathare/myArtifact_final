# Implementation Plan – Final Runtime Evidence Collection

This plan focuses on capturing authoritative evidence from the production environment to diagnose the `PERMISSION_DENIED` error in Firestore.

## User Review Required

> [!IMPORTANT]
> **Real-time Capture**: To execute Priority 1, I need to monitor Logcat while you reproduce the issue on a connected device (physical or production-flavor emulator). Please ensure the device is connected and the app is running the production flavor.

## Proposed Strategy

The investigation will follow a branching logic based on the evidence captured from `INVESTIGATION_LOG`.

### Priority 1: Runtime Log Collection [EXECUTION START]
- **Tool**: `read_logcat` with filters for `INVESTIGATION_LOG`.
- **Target Data**:
    - `AuthUID` (Currently logged user)
    - `PathUID` (UID used in the Firestore document path)
    - `DocumentPath` (The full resource path)
    - `ArtifactId` (The artifact being engaged)
    - `Success` / `ErrorCode` / `ErrorMessage`
    - `OperationMode` (UPSERT_MERGE vs READ)

### Priority 2: Security & Infrastructure (If UID matches path)
If `AuthUID == PathUID` but the request still fails:
- **App Check Enforcement**: Verify if the `PERMISSION_DENIED` is actually an App Check rejection (often identical error code).
- **Rules Mismatch**: Check if `isCommentUnlocked` or `exists()` checks are failing due to missing data or backend logic.
- **Project Scope**: Verify the `Firebase Project ID` logged matches the console where rules are deployed.

### Priority 3: State & Identity (If UID mismatch)
If `AuthUID != PathUID`:
- **Navigation/ViewModel**: Audit `PlayerViewModel` and `SavedStateHandle` to see how the `userId` is passed.
- **Background Workers**: Check if `InteractionSyncWorker` is using a stale or incorrect identity.
- **Auth Race Conditions**: Determine if the listener is attached before the `AuthStateListener` has fully resolved the user.

## Verification Plan

### Manual Verification
1.  **Logcat Analysis**: I will present a table of the captured logs for your review.
2.  **Root Cause Hypothesis**: Based on the data, I will provide a single, evidence-backed conclusion.

### Automated Checks
- Verify `build.gradle.kts` matches the `Firebase Project ID` found in the logs.
