# Investigation Results - Comment Composer Focus Failure

The investigation has identified a primary root cause and a secondary infrastructure issue preventing the `CommentComposer` from gaining focus.

## Findings

### 1. Primary Root Cause: Interaction Disabled
Runtime logging confirmed that the `BasicTextField` (via `MindfulTextField`) is received an `enabled=false` state when the `CommentSheet` is opened.
- **Evidence**: `COMMENT_FOCUS: CommentComposer: unlockState=VERIFYING isUnlocked=false`
- **Effect**: A disabled `BasicTextField` does not gain focus or trigger the IME (keyboard) upon tapping.
- **Logic**: The `CommentComposer` only enables the text field if the `unlockState` is `CommentUnlockState.UNLOCKED`.

### 2. Secondary Root Cause: Stuck Unlock State (Repository Crash)
The `unlockState` remains stuck in `VERIFYING` (or fails to transition to `UNLOCKED`) because of a runtime exception in the data layer.
- **Evidence**: `java.lang.RuntimeException: Field 'engagementState' is not a java.lang.String` in `FirestoreEngagementRepository.kt:100`.
- **Reason**: The code expects `engagementState` to be a String in Firestore, but it is currently stored as a different type (likely an integer or reference).
- **Effect**: The `callbackFlow` observing the unlock status closes with an error, preventing the UI from ever receiving the `UNLOCKED` state.

### 3. Focus Pipeline Verification
When the `enabled` flag was temporarily forced to `true` in `CommentComposer.kt`:
- **Pointer Events**: Pointer events were successfully received by the field.
- **Focus Events**: The field successfully gained focus (`isFocused=true hasFocus=true`).
- **IME**: The keyboard did not immediately appear in some tests, suggesting potential focus-management issues within the `ModalBottomSheet` environment once the interactivity is restored.

## Phase Progress Summary

| Phase | Status | Result |
| :--- | :--- | :--- |
| **PHASE 1 — Verify Enabled State** | ✅ Confirmed | `enabled=false` due to `unlockState=VERIFYING`. |
| **PHASE 2 — Verify Pointer Events** | ✅ Confirmed | Events reach the field when enabled. |
| **PHASE 3 — Verify Focus Events** | ✅ Confirmed | `onFocusChanged` fires with `true` when enabled. |
| **PHASE 5 — Verify FocusManager** | ✅ Confirmed | Found crash in `FirestoreEngagementRepository` preventing state updates. |
| **PHASE 8 — Verify Layout Bounds** | ✅ Confirmed | Size is healthy (`1136 x 123`). |
| **PHASE 11 — Verify Recompositions** | ⚠️ High | Excessive recompositions noted during focus transitions. |

## Recommendation
1.  **Fix Repository Crash**: Modify `FirestoreEngagementRepository.kt` to handle non-string values for `engagementState` gracefully.
2.  **Unlock Logic**: Ensure the transition to `UNLOCKED` is reliable.
3.  **UI Refinement**: Proceed with the structural refactoring of `MindfulTextField` (from `Box` to `Column`) and add explicit `FocusRequester` to ensure robustness in the `ModalBottomSheet` lifecycle.
