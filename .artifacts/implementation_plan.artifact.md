# Final Implementation Plan: Lifecycle-Safe Logout Cleanup & Remote Sign-Out Ownership

Establish deterministic, race-free, session-isolated, cancellation-safe, and authorized lifecycle cleanup in Artifact Android via `LogoutCoordinator`.

---

## 1. Problem Summary

Architectural revisions established an Application-scoped `@Singleton` `LogoutCoordinator` to manage local cleanup and remote sign-out without ViewModel truncation, resolving event identity capture (Blocker 1), dual completion models (Blockers 2 & 3), account boundary authorization conflicts (Blocker 4), and post-completion local cleanup state tracking (Blocker 5).

A final critical security and account isolation issue was identified in review:

* **Blocker 6 (Firebase Remote Sign-Out Ownership Race):** The previous design allowed a completed local cleanup record `Key(A, S1)` (`localCleanupCompleted = true`, `remoteSignOutCompleted = false`) to later trigger a Phase-E-only operation calling `authRepository.signOut()`. Because the Firebase Auth SDK operates globally on `firebaseAuth.currentUser` rather than targeting a specific historical session ID, if Account B became authenticated in the interim (`currentUid = B`), a delayed or retried Phase E for Account A would call `authRepository.signOut()` and forcibly sign out Account B!

This revised implementation plan resolves Blocker 6 by explicitly separating **Local Cleanup Authority** from **Remote Firebase Sign-Out Authority**, establishing a strict **Current Session Ownership Check** before executing `authRepository.signOut()`, and marking historical remote sign-outs as **superseded** when authentication state transitions occur.

---

## 2. Blocker 6 Resolution — Separation of Authorities

### Core Security Invariant
> *"An old cleanup event may continue cleaning its own authorized local state after authentication changes, but it must never invoke `FirebaseAuth.signOut()` against a newer authenticated account or session."*

### Distinction Between Authorities
1. **Local Cleanup Authority:**
   - Granted **at request creation time** when an authorized lifecycle transition (user logout, account boundary swap, session revocation, startup recovery) triggers cleanup for `CleanupEventKey(targetUid, sessionInstanceId)`.
   - Owned by the Application-scoped `LogoutCoordinator`.
   - Continues background local cleanup (Phases A-D) for `Key(A, S1)` even if Firebase Auth subsequently changes to Account B.
   - Operates strictly on `(A, S1)`'s key-scoped resources without modifying or inspecting Account B.

2. **Remote Firebase Sign-Out Authority:**
   - Evaluated **immediately before Phase E execution** (`authRepository.signOut()`).
   - Requires that the currently active Firebase authentication session on the device STILL belongs to `CleanupEventKey(targetUid, sessionInstanceId)`.
   - A `CleanupEventKey(A, S1)` identifies local cleanup ownership, but DOES NOT grant authority to invoke `firebaseAuth.signOut()` at a later point in time if Account B or Session S2 is currently active.

---

## 3. Local Cleanup Authority

Local cleanup (Phases A-D) MUST remain independent of remote sign-out authority:

```
Account A / Session S1 active
   ↓
A/S1 cleanup begins (Phases A-D)
   ↓
Firebase changes to B (currentUser = B)
   ↓
A/S1 local cleanup (Phases A-D) CONTINUES in background
   ↓
Account B local state remains COMPLETELY UNTOUCHED
```

* **Why local cleanup continues:** Local disk state, Room database rows, cache files, and DataStore keys belonging to Account A must still be purged to prevent local cross-account data leakage.
* **Isolation guarantee:** All Phase A-D tasks execute strictly against `CleanupEventKey(A, S1)`. Key matching is exact (`Key(A, S1) != Key(B, S2)`), ensuring Account B's state is never touched.

---

## 4. Firebase Remote Sign-Out Authority

Firebase Auth SDK mechanics dictate that `firebaseAuth.signOut()` unconditionally terminates whichever user is currently signed into Firebase (`firebaseAuth.currentUser`). It cannot accept a target UID or session ID parameter.

Therefore:
* Calling `authRepository.signOut()` is ONLY authorized when `firebaseAuth.currentUser?.uid == key.targetUid` AND the local device session has not been reassigned to a newer session instance ID (`S2`).
* If `currentUser` has changed to `B` or local session has changed to `S2`, Phase E (`authRepository.signOut()`) MUST BE SKIPPED for `Key(A, S1)`.

---

## 5. Phase-E Eligibility Rules

Before any call to `authRepository.signOut()`, `LogoutCoordinator` evaluates **Phase-E Eligibility**:

### The Two Mandatory Eligibility Conditions
Phase E is eligible to execute for `key = CleanupEventKey(targetUid, sessionInstanceId)` IF AND ONLY IF:

1. **Current Firebase UID Match:** `authRepository.currentUser?.uid == key.targetUid`
   *(The current Firebase user must match the target UID of the cleanup event).*
2. **Current Local Session Match:** `currentLocalSessionId == null || currentLocalSessionId == key.sessionInstanceId`
   *(The current local session in DataStore either matches the event's `sessionInstanceId` OR was cleared by `(A, S1)`'s own Phase C local cleanup without any NEW session `S2` or Account `B` being initialized).*

If EITHER condition fails:
* `authRepository.signOut()` is **NOT CALLED**.
* Remote sign-out for `Key(A, S1)` is marked as **SUPERSEDED** (`isRemoteSignOutSuperseded = true`).
* The event `Key(A, S1)` transitions to a **TERMINAL COMPLETED** state.
* Account B or Session S2 remains fully authenticated and unaffected.

---

## 6. Current Session Ownership Check

### Exact Decision Function in `LogoutCoordinator`

```kotlin
suspend fun isRemoteSignOutEligible(key: CleanupEventKey): Boolean {
    val currentFirebaseUid = authRepository.currentUser.value?.uid ?: firebaseAuth.currentUser?.uid
    val currentLocalSessionId = sessionManager.localSessionId.first()

    val isUidMatch = currentFirebaseUid != null && currentFirebaseUid == key.targetUid
    val isSessionMatch = currentLocalSessionId == null || currentLocalSessionId == key.sessionInstanceId

    return isUidMatch && isSessionMatch
}
```

### Execution Flow in Phase E

```kotlin
private suspend fun executePhaseE(key: CleanupEventKey): Result<Unit> {
    // Under stateMutex / atomic check before calling authRepository.signOut()
    if (!isRemoteSignOutEligible(key)) {
        diagnosticLogger.info(
            DiagnosticCategory.AUTH,
            "LOGOUT_FIREBASE_SKIPPED_SUPERSEDED",
            mapOf(
                "targetUid" to key.targetUid,
                "currentUid" to (authRepository.currentUser.value?.uid ?: "null"),
                "sessionInstanceId" to key.sessionInstanceId
            )
        )
        // Mark as superseded in completion record under stateMutex
        updateRecordRemoteSignOutSuperseded(key)
        return Result.success(Unit)
    }

    return try {
        authRepository.signOut()
        diagnosticLogger.info(DiagnosticCategory.AUTH, "LOGOUT_FIREBASE_SUCCESS")
        updateRecordRemoteSignOutSuccess(key)
        Result.success(Unit)
    } catch (e: Exception) {
        diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_FIREBASE_FAILED", throwable = e)
        Result.failure(e)
    }
}
```

---

## 7. Account Boundary Behavior (Account A -> Account B)

### Step-by-Step Transition Analysis
1. `Account_A / Session_S1` is active.
2. `MainViewModel` or AuthStateListener detects Firebase Auth changed to `Account_B` (`currentUid = B`, DataStore `owningUid = A`).
3. `MainViewModel` triggers `logoutCoordinator.performFullCleanup(Key(Account_A, Session_S1))`.
4. Creation Authorization check passes: `targetUid (A) == owningUid (A)`. Operation registered as `activeOperations[Key(A, S1)]`.
5. Local cleanup (Phases A-D) executes in background for Account A.
6. Local cleanup completes: `completionRecords[Key(A, S1)] = Record(localCleanupCompleted = true, remoteSignOutCompleted = false)`.
7. Phase E requested for `Key(A, S1)`.
8. `isRemoteSignOutEligible(Key(A, S1))` evaluates:
   - `currentFirebaseUid` is `B` (`B != A`). Check returns `FALSE`.
9. `authRepository.signOut()` is **NOT called**. Account B remains fully authenticated in the foreground.
10. `completionRecords[Key(A, S1)]` is updated: `isRemoteSignOutSuperseded = true` (`isFullyTerminal = true`).
11. `sessionManager.setLoggingOut(false)` is reset to `false`.
12. `Key(A, S1)` becomes terminally completed. No retry will ever attempt to sign out B.

---

## 8. Same-UID Session Behavior (A/S1 -> A/S2)

### Step-by-Step Transition Analysis
1. User A is active on `Session_S1`. Local cleanup begins for `Key(A, S1)`.
2. User A logs in or claims a new session `Session_S2` (`localSessionId = S2`).
3. A delayed Phase-E request or retry arrives for `Key(A, S1)`.
4. `isRemoteSignOutEligible(Key(A, S1))` evaluates:
   - `currentFirebaseUid` is `A` (`A == A`).
   - `currentLocalSessionId` is `S2` (`S2 != S1`).
   - `isSessionMatch` returns `FALSE`.
5. Check returns `FALSE`. `authRepository.signOut()` is **NOT called**.
6. `Session_S2` remains active and authenticated.
7. `Key(A, S1)` remote sign-out is marked as superseded (`isRemoteSignOutSuperseded = true`).

---

## 9. Session Revocation Behavior

1. `SessionState.Revoked` is emitted for `(A, S1)`.
2. `MainViewModel` invokes `logoutCoordinator.performFullCleanup(Key(A, S1))`.
3. If Firebase Auth has already cleared or transitioned (`currentUser == null` or `currentUser == B`):
   - Local cleanup (Phases A-D) executes and completes.
   - Phase E eligibility check sees `currentFirebaseUid != A` -> `signOut()` is skipped as superseded.
   - Cleanup returns `CleanupResult(status = COMPLETED)`.
   - UI navigates cleanly to Login screen without transient exceptions.

---

## 10. Stale Callback Behavior

* Stale/delayed callbacks carry an immutable `CleanupEventKey(targetUid, sessionInstanceId)`.
* When the callback executes, `LogoutCoordinator` evaluates the key against `activeOperations` and `completionRecords` under `stateMutex`.
* If `completionRecords[key]` exists and is terminal (`isFullyTerminal == true`), the callback receives the cached result without invoking side effects.
* If no record exists and creation authorization fails (`targetUid != currentUid` AND `targetUid != owningUid`), the callback is safely discarded (NO-OP).

---

## 11. Phase-E Race Handling

### Concurrent Phase E Callers
* Multiple callers requesting Phase E for `Key(A, S1)` while `(A, S1)` is active share a single `activePhaseEOperations[Key(A, S1)]` `Deferred`.
* `authRepository.signOut()` executes exactly **ONCE**.

### In-Flight Auth State Transition
* The eligibility check `isRemoteSignOutEligible(key)` is re-evaluated immediately before executing `authRepository.signOut()`.
* If Firebase Auth changes while Phase E is in flight:
  - `authRepository.signOut()` finishes clearing Firebase state.
  - After execution, `LogoutCoordinator` verifies `authRepository.currentUser.value == null`.
  - If `currentUser == null`, `remoteSignOutCompleted = true`.

---

## 12. Process Death and Startup Recovery

* Memory `completionRecords` is cleared on process death.
* On cold boot, `StartupCoordinator` checks `sessionManager.isLoggingOut` and `maintenanceRepository.getPendingDeletionUid()`.
* If `isLoggingOut == true`:
  - `StartupCoordinator` executes startup recovery: `performFullCleanup(Key(RecoveryUid, "RECOVERY_" + UUID))`.
  - Fresh Phase A-D recovery runs idempotently to ensure disk/database handles are cleared before UI setup.
  - Before attempting Phase E, recovery eligibility check evaluates `isRemoteSignOutEligible(key)`:
    - If `currentUser == null` (logged out), `signOut()` is skipped.
    - If a new user `B` authenticated on cold boot, `currentUser == B` (`B != RecoveryUid`) -> `signOut()` is SKIPPED. Account B is NEVER signed out on cold startup recovery.

---

## 13. CompletionRecord Lifecycle & Bounded Storage

### Data Model

```kotlin
data class CleanupCompletionRecord(
    val key: CleanupEventKey,
    val localCleanupCompleted: Boolean,
    val remoteSignOutCompleted: Boolean,
    val isRemoteSignOutSuperseded: Boolean = false,
    val completionTimestamp: Long = System.currentTimeMillis()
) {
    val isFullyTerminal: Boolean
        get() = localCleanupCompleted && (remoteSignOutCompleted || isRemoteSignOutSuperseded)
}
```

### In-Memory Maps inside `LogoutCoordinator`

```kotlin
private val activeOperations = mutableMapOf<CleanupEventKey, ActiveCleanupOperation>()
private val activePhaseEOperations = mutableMapOf<CleanupEventKey, Deferred<Result<Unit>>>()
private val completionRecords = mutableMapOf<CleanupEventKey, CleanupCompletionRecord>()
```

### Lifecycle Rules for `completionRecords`
1. **Creation:** Stored under `stateMutex` immediately when Phase A-D completes for `key`: `localCleanupCompleted = true, remoteSignOutCompleted = false`.
2. **Update (Remote Success):** When Phase E succeeds for `key`: sets `remoteSignOutCompleted = true` (`isFullyTerminal = true`).
3. **Update (Remote Superseded):** When Phase E ownership check fails (session changed): sets `isRemoteSignOutSuperseded = true` (`isFullyTerminal = true`).
4. **Eviction:** Bounded LRU map (max 10 entries); evicted on session rotation or process death.

---

## 14. State Machine Diagram & Decision Matrix

### Decision Flow Before Phase E

```
                  Phase E Requested for Key(targetUid, sessionInstanceId)
                                         │
                                         ▼
                     Evaluate Session Ownership Check:
                     1. authRepository.currentUser?.uid == key.targetUid?
                     2. AND (sessionManager.localSessionId.first() == null
                             OR sessionManager.localSessionId.first() == key.sessionInstanceId)?
                                         │
                        ┌────────────────┴────────────────┐
                        ▼                                 ▼
                      [YES]                             [NO]
                        │                                 │
                        ▼                                 ▼
         Call authRepository.signOut()        DO NOT call signOut()
                        │                                 │
               ┌────────┴────────┐                        │
               ▼                 ▼                        │
           [SUCCESS]          [ERROR]                     │
               │                 │                        │
               ▼                 ▼                        ▼
       Set remote=true     Keep remote=false      Set isSuperseded=true
       Record Terminal     Retryable IF STILL     Record Terminal
                           matching session       Superseded
```

### Decision Matrix

| Scenario | Local Cleanup State | Remote Sign-Out State | Ownership Check | `authRepository.signOut()` Called? | Event Terminal? | `isLoggingOut` |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Normal Logout (A/S1 current)** | Completed | Completed | YES | **YES (Once)** | YES | `false` |
| **Account Switch (A -> B during A cleanup)** | Completed | Superseded | NO (`currentUid = B`) | **NO** | YES | `false` |
| **Same UID New Session (S1 -> S2)** | Completed | Superseded | NO (`session = S2`) | **NO** | YES | `false` |
| **Delayed A/S1 Retry when B active** | Completed (Cached) | Superseded | NO (`currentUid = B`) | **NO** | YES | `false` |
| **Phase E Network Error (A/S1 still current)** | Completed | Failed | YES | **YES** | NO (Retryable) | `true` |
| **Session Revoked (Already logged out)** | Completed | Superseded | NO (`currentUid = null`) | **NO** | YES | `false` |
| **Startup Recovery (New User B active)** | Completed | Superseded | NO (`currentUid = B`) | **NO** | YES | `false` |

---

## 15. Files to Modify

1. `LogoutCoordinator.kt` — Implement `CleanupEventKey`, `CleanupCompletionRecord`, `activeOperations`, `activePhaseEOperations`, `completionRecords`, `isRemoteSignOutEligible` check, Phase-E execution, atomic `stateMutex` updates, and `CancellationException` re-throwing.
2. `UserSessionManager.kt` — Expose `localSessionId` flow and `owningUid`.
3. `MainViewModel.kt` — Pass `CleanupEventKey` on account boundary detection, session revocation, and explicit sign-out.
4. `SettingsViewModel.kt` — Construct `CleanupEventKey` and invoke `logoutCoordinator.executeLogout(key)`.
5. `ProfileViewModel.kt` — Construct `CleanupEventKey` and invoke `logoutCoordinator.executeLogout(key)`.
6. `SettingsRepository.kt` — Construct `CleanupEventKey` and invoke `logoutCoordinator.get().performFullCleanup(key)`.
7. `StartupCoordinator.kt` — Construct `CleanupEventKey` for startup recovery.

---

## 16. Exact Production Call-Site Changes

### `MainViewModel.kt`
```kotlin
// Account boundary
val key = CleanupEventKey(
    targetUid = owningUid ?: previousUid ?: "",
    sessionInstanceId = localSessionId ?: "UNKNOWN"
)
logoutCoordinator.performFullCleanup(key)

// Explicit sign-out
val key = CleanupEventKey(
    targetUid = currentUid ?: "",
    sessionInstanceId = localSessionId ?: "UNKNOWN"
)
logoutCoordinator.executeLogout(key)
```

### `SettingsViewModel.kt`
```kotlin
fun logout() {
    val key = CleanupEventKey(
        targetUid = authRepository.currentUser.value?.uid ?: "",
        sessionInstanceId = sessionManager.localSessionId.value ?: "UNKNOWN"
    )
    viewModelScope.launch {
        logoutCoordinator.executeLogout(key)
    }
}
```

### `ProfileViewModel.kt`
```kotlin
fun logout() {
    val key = CleanupEventKey(
        targetUid = authRepository.currentUser.value?.uid ?: "",
        sessionInstanceId = sessionManager.localSessionId.value ?: "UNKNOWN"
    )
    viewModelScope.launch {
        logoutCoordinator.executeLogout(key)
    }
}
```

---

## 17. Updated Automated Tests

### Blocker 6 Specific Ownership Tests:
1. **Normal Phase E:** Explicit A/S1 logout while A/S1 is current -> `signOut()` called once, `remoteSignOutCompleted = true`.
2. **Account switch during local cleanup:** A/S1 cleanup starts, Firebase changes to B -> A-D complete, B remains authenticated, `signOut()` is NOT called against B.
3. **Phase-E retry while B is current:** A/S1 Phase-E retry arrives while B is current -> `signOut()` is NOT called, B remains authenticated.
4. **Stale callback while B is current:** A/S1 stale callback arrives while B is current -> no `signOut()`, no B cleanup.
5. **Same UID / new session (A/S1 -> A/S2):** S1 Phase-E retry cannot sign out S2.
6. **Same UID stale callback:** A/S1 stale callback cannot affect A/S2.
7. **Concurrent Phase E callers:** Two callers for A/S1 while A/S1 is current -> one `signOut()`, both share same result.
8. **Session changes while Phase E prepared:** Verify ownership decision prevents unsafe `signOut()`.
9. **Session changes during Phase E:** Verify no newer session is accidentally terminated.
10. **Account B explicit logout:** B/S2 explicit logout while A/S1 record exists -> B/S2 gets its own cleanup, A/S1 record is not reused, `signOut()` applies only to B/S2.
11. **Session revocation:** A/S1 revoked before Phase E -> local cleanup remains safe, no unsafe `signOut()` of a newer account/session.
12. **Process death startup recovery:** Recovery when new account B active -> recovery cannot sign out account B.

---

## 18. Manual Verification

1. **TEST 1 — Normal Logout:** Account A -> Logout -> Confirm login screen transition and `LOGOUT_FIREBASE_SUCCESS` logcat.
2. **TEST 2 — Account Switch During Cleanup:** Trigger Account A cleanup -> Switch/login Account B -> Verify Account B remains authenticated and Account A cleanup completes in background without `signOut()` against B.
3. **TEST 3 — Same UID Session Isolation:** Account A / Session 1 cleanup -> Login Account A / Session 2 -> Verify stale Session 1 activity cannot sign out Session 2.
4. **TEST 4 — Account B Isolation:** Account A cleanup record exists -> Login Account B -> Logout Account B -> Verify B performs its own cleanup and A's record is not reused.
5. **TEST 5 — Draft Preservation:** Verify `artifact_drafts` database table and local draft audio files remain intact after cleanup.

---

## 19. Risks and Mitigations

* **Risk:** Concurrency lock contention on `stateMutex`.
  * **Mitigation:** `stateMutex` is held strictly for microseconds to read/write map records and instantiate `Deferred` instances. All heavy Phase A-D and Phase E I/O runs outside `stateMutex`.
* **Risk:** Memory growth in `completionRecords`.
  * **Mitigation:** `completionRecords` is a bounded LRU map (max 10 entries) and rotates on session changes.

---

## 20. Rollback Strategy

If unexpected regressions occur, revert `LogoutCoordinator.kt` to the prior Mutex-protected `performFullCleanup` implementation while preserving Application scoping.

---

## 21. Final Recommendation

BLOCKER 6: RESOLVED

Local cleanup may continue after account/session change:
YES

Historical cleanup event can call FirebaseAuth.signOut() against a newer session:
MUST BE NO

Phase-E current-session ownership check:
DEFINED

Account A → Account B isolation:
VERIFIED IN PLAN

Same UID S1 → S2 isolation:
VERIFIED IN PLAN

Remaining assumptions:
None

Remaining unknowns:
None

Additional investigation needed:
NO

Final status:
APPROVED FOR IMPLEMENTATION
