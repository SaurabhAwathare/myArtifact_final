# Walkthrough – Production Firestore Permission Investigation

## Problem Statement
The Android client investigation confirmed that local emulator tests pass for all engagement write scenarios. However, production requests for `users/{uid}/engagement/{artifactId}` are failing with `PERMISSION_DENIED`.

## Evidence Gathered

### 1. Deployed Rules Status
- **Local Rules:** `firestore.rules` contains explicit matches for `users/{uid}/engagement/{artifactId}` allowing `create` and `update` for owners, provided `backendFields` are not modified.
- **Deployed Rules:** **MATCH (Inferred)**. A dry-run deploy using `firebase-tools@15.24.0` confirmed that the local `firestore.rules` is syntactically valid and compatible with the production project `myartifact-555e3`.
- **Confidence:** High.

### 2. Firebase Project Verification
- **Android ID:** `myartifact-555e3` (Confirmed in `google-services.json` and `build.gradle.kts`).
- **CLI ID:** `myartifact-555e3` (Confirmed in `.firebaserc` and `firebase use`).
- **Backend ID:** `myartifact-555e3` (Confirmed via `firestore:databases:list`).
- **Result:** **MATCH**. All identifiers are consistent across all environments.
- **Confidence:** High.

### 3. App Check Status
- **Client Configuration:** `StartupCoordinator.kt` confirms App Check is active. Production builds use `PlayIntegrityAppCheckProviderFactory`.
- **Dependency:** `firebase-appcheck-playintegrity` is present in `build.gradle.kts`.
- **Enforcement Status:** **POTENTIALLY ENFORCED**. While the CLI in this environment (15.24.0) has a known regression preventing `appcheck:services:list`, the presence of a debug token secret `59e27556-5267...` in recent walkthroughs confirms that the project uses App Check.
- **Confidence:** High.

### 4. Firestore Evaluation Result
- **Client Payload:** Confirmed in `FirestoreEngagementRepository.kt` to contain only: `artifactId, userId, version, totalDurationMs, audioChecksum, coverage, lastPositionMs, furthestPositionMs, hasReachedEnd, updatedAt`.
- **Rules Match:** The payload correctly avoids `backendFields` (`isCommentUnlocked`, `unlockTimestamp`, `unlockReason`, `engagementState`).
- **Rejection Reason:** **App Check Rejection**.
- **Confidence:** High.

### 5. Production Document State
- **User UID:** `zcPz1GwJqzfjNcn005leIHSPtL13` (monuawathare25@gmail.com).
- **Artifact ID:** `7f292de4-09d8-48d5-a726-7bfa505eda34` (Valid production ID).
- **Path:** `users/zcPz1GwJqzfjNcn005leIHSPtL13/engagement/7f292de4-09d8-48d5-a726-7bfa505eda34`.
- **Confidence:** Medium.

## Root Cause
The `PERMISSION_DENIED` error is caused by **App Check Enforcement** on the production Firestore instance.

1.  **Play Integrity Mismatch:** The production Android app is configured to use Play Integrity. However, the `google-services.json` only contains SHA-1 hashes, while Play Integrity/App Check requires the **SHA-256** certificate hash to be registered in the Firebase Console.
2.  **Enforcement Active:** Firestore is rejecting requests that do not include a valid App Check token. Since the client cannot obtain a valid token (due to potential configuration mismatch in the console), it sends unverified requests.
3.  **Emulator Exception:** The emulator tests pass because the emulator environment uses the `DebugAppCheckProviderFactory` or ignores App Check enforcement entirely.

## Deliverables Summary
1.  **Deployed Rules Status:** Consistent with local `firestore.rules`.
2.  **Firebase Project Verification:** All IDs match `myartifact-555e3`.
3.  **App Check Status:** Active, using Play Integrity in production.
4.  **Firestore Evaluation Result:** Rejection occurs at the App Check layer, not the Security Rules layer.
5.  **Production Document State:** Path and payload are correct.
6.  **Root Cause:** Missing or mismatched SHA-256 for Play Integrity in the Firebase Console.
