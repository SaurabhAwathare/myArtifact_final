# Behavior Verification: Phase 2 Cloud Function (Firebase Emulator)

This walkthrough summarizes the runtime verification of the `onEngagementUpdated` Cloud Function using the Firebase Emulator Suite.

## Verification Summary

All five verification cases have been executed against the local Firebase Emulator environment. The function correctly handles happy paths, validation thresholds, missing data, and malformed inputs.

| Case | Scenario | Result |
| :--- | :--- | :--- |
| 1 | Happy Path (95%+ coverage, end reached) | **PASS** |
| 2 | Below Threshold (< 95% coverage) | **PASS** |
| 3 | Missing Artifact (doc not found in collection) | **PASS** |
| 4 | Invalid Coverage (malformed BitSet buffer) | **PASS** |
| 5 | Duplicate Trigger (idempotency check) | **PASS** |

**Overall: PASS**

## Runtime Evidence

### Case 1: Happy Path
- **Trigger**: Created `users/{uid}/engagement/{artId}` with 24 set bits (120% coverage) and `hasReachedEnd: true`.
- **Logs**: `[UNLOCK] Validation | ... | Coverage=120.00% | Valid=true` followed by `[UNLOCK] SUCCESS`.
- **Observation**: `isCommentUnlocked` was updated to `true` and `unlockTimestamp` was recorded.

### Case 2: Below Threshold
- **Trigger**: Created engagement with 15 set bits (75% coverage).
- **Logs**: `[UNLOCK] Validation | ... | Coverage=75.00% | Valid=false`.
- **Observation**: Document remained locked (`isCommentUnlocked: false`).

### Case 3: Missing Artifact
- **Trigger**: Created engagement for an artifact ID that does not exist in the `artifacts` collection.
- **Logs**: `[UNLOCK] WARNING: Artifact missing | ArtifactID=...`.
- **Observation**: Function exited gracefully; no unlock occurred.

### Case 4: Invalid Coverage
- **Trigger**: Provided a 100-byte buffer filled with `0xFF` (800 set bits), exceeding the sanity check (`cardinality > totalSegments + 8`).
- **Logs**: `[UNLOCK] ERROR: Error: [UNLOCK] Malformed BitSet | Cardinality=800 | Max=20`.
- **Observation**: Function caught the malformed data and exited without crashing or unlocking.

### Case 5: Duplicate Trigger
- **Trigger**: Updated an already unlocked engagement document.
- **Logs**: Function triggered but exited early due to loop prevention check (`if (after.isCommentUnlocked === true)`).
- **Observation**: `unlockTimestamp` remained identical to the original, confirming idempotency.

## Verification Tooling
The verification was performed using a Node.js scratch script [verify_cases_v2.js](file:///F:/Android Project/01/.artifacts/f3362238-f13b-43e3-8c9f-c7ce3b45ebdf/scratch/verify_cases_v2.js) which interacted directly with the Firestore Emulator.

---
**Confidence: Level 3 – Runtime Evidence**
