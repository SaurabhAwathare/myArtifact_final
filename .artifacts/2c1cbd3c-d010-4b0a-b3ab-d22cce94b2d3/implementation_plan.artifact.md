# Implementation Plan - Fix FirestoreEngagementRepository Crash & Restore Flow

Fix the `java.lang.RuntimeException: Field 'engagementState' is not a java.lang.String` crash (or prevent `UNKNOWN` state from hidden Map types) in `FirestoreEngagementRepository`. This ensures the `callbackFlow` remains active and allows the UI to transition to `UNLOCKED` based on the authoritative source of truth.

## User Review Required

> [!IMPORTANT]
> **Source of Truth Analysis**:
> - Backend code (`functions/src/index.ts`) writes to both `isCommentUnlocked: true` (Boolean) and `"engagementState.unlocked": true` (Nested Map field).
> - The Android UI (`CommentViewModel.deriveUnlockState`) primarily uses `evidence.unlockStatus.isCommentUnlocked` to transition to `UNLOCKED`.
> - However, the `FirestoreEngagementRepository` crashes during object construction because it attempts to parse `engagementState` as a String when it has become a Map. This crash prevents the `isCommentUnlocked` field from ever being read.

## Proposed Changes

### [Component] Repository Layer

#### [MODIFY] [FirestoreEngagementRepository.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/repository/FirestoreEngagementRepository.kt)

- Refactor `observeRemoteUnlockStatus` to use a type-aware parser for `engagementState`.
- **Logic**:
    1. Read field as `Any?`.
    2. If `String`: Parse using `EngagementState.fromString`.
    3. If `Map`: Check for `unlocked == true` -> `EngagementState.UNLOCKED`.
    4. Otherwise: Fallback to `UNKNOWN`.
- **Resilience**: Wrap the parsing block in a `try-catch` that logs errors but **does not close the flow**, ensuring the `isCommentUnlocked` boolean can still unlock the UI even if the engagement state schema is unexpected.
- **Diagnostics**:
    - Log the runtime type and raw value of `engagementState`.
    - If `EngagementState` results in `UNKNOWN`, log the entire document data (temporary, for development only) to reveal the actual schema.

```kotlin
// Proposed parsing logic
val rawEngagement = snapshot.get("engagementState")
val parsedState = when (rawEngagement) {
    is String -> EngagementState.fromString(rawEngagement)
    is Map<*, *> -> {
        // Handle nested structure: { unlocked: true }
        if (rawEngagement["unlocked"] == true) EngagementState.UNLOCKED
        else EngagementState.LOCKED
    }
    else -> EngagementState.UNKNOWN
}

// Diagnostic Logging (Temporary for investigation)
diagnosticLogger.info(
    DiagnosticCategory.FIRESTORE,
    "ENGAGEMENT_SCHEMA_INSPECTION",
    mapOf(
        "artifactId" to artifactId,
        "rawType" to (rawEngagement?.javaClass?.simpleName ?: "null"),
        "rawValue" to (rawEngagement?.toString() ?: "null"),
        "parsedState" to parsedState.name,
        "isCommentUnlocked" to (snapshot.getBoolean("isCommentUnlocked") ?: false)
    )
)
```

## Verification Plan

### Automated Tests
- I will verify the logic by checking that the `callbackFlow` does not close.

### Manual Verification
1.  **Check Logcat**: Look for `ENGAGEMENT_SCHEMA_INSPECTION`.
2.  **Verify Unlock**: Confirm the Comment Composer unlocks when the backend writes `{unlocked: true}` or `isCommentUnlocked: true`.
3.  **Expected Logcat output**:
    ```text
    [FIRESTORE] ENGAGEMENT_SCHEMA_INSPECTION: {artifactId=..., rawType=HashMap, rawValue={unlocked=true}, parsedState=UNLOCKED, isCommentUnlocked=true}
    ```
