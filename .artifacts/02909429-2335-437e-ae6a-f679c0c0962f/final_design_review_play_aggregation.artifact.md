# Final Design Review – Play Event Architecture & Aggregate Policy

**Status**: ⚠ Ready after Minor Design Changes
**Confidence Level**: Level 3 — Code Evidence

This document provides a final architectural review of the play event tracking and aggregation system before implementation begins.

---

## Phase 1 – Play Event Definition

Based on current code evidence in [FeedViewModel.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/ui/feed/FeedViewModel.kt#L442), the definition of a play event is as follows:

| Question | Current Behavior / Definition | Decision |
| :--- | :--- | :--- |
| **When to record?** | On "Play Start" when an artifact is selected. | **Immediate Intent** |
| **Minimum %?** | None. Recording happens before playback starts. | **No** (Simplicity) |
| **Seeking?** | Does not trigger a new record event. | **No** |
| **Replay?** | Only counts if switching between different artifacts. | **Limited** (Throttled by ID) |
| **Paused/Resumed?** | Specifically checked and skipped via `audioPlayer.currentArtifact` check. | **No** |
| **Offline?** | Supported via Firestore persistence and deterministic IDs. | **Yes** |

> [!NOTE]
> While [PersonalizationEngine.kt](file:///F:/Android Project/01/app/src/main/java/com/saurabh/artifact/service/PersonalizationEngine.kt) supports more nuanced metrics like `completionRate`, the **Global Play Count** remains a simple "Intent to Play" metric for scalability and reduced complexity.

---

## Phase 2 & 3 – Idempotency & Document ID Evaluation

**Proposed Identifier**: `${userId}_${artifactId}_${timestamp_bucket}`

### Assessment:
- **Deterministic**: Yes. Re-submission from the same user for the same artifact in the same bucket will result in the same document ID.
- **Idempotency**: Firestore `set` (or `create`) ensures that retries (client-side or network) do not create duplicate records.
- **Timestamp Bucket**: Recommended format is `YYYY-MM-DD-HH` (Hourly).
    - *Why Hourly?* Throttles malicious spam (bot-like behavior) while allowing legitimate repeat listens by fans over time to be counted.
- **Cleanup**: Clean and efficient. [index.ts](file:///F:/Android Project/01/functions/src/index.ts) can target `artifact_plays` with an `artifactId` query.

---

## Phase 4 – Collection Ownership

**Collection**: `artifact_plays` (Top-level)
**Category**: Engagement Events

| Aspect | Recommendation |
| :--- | :--- |
| **Repository** | `ArtifactEngagementRepository` (owns interaction loop). |
| **Cloud Function** | `onPlayCreated` (Triggered on document creation). |
| **Retention** | Long-term (Analytics value) or TTL (e.g. 30 days) if only used for delta aggregation. |
| **Cleanup** | Must be added to `onArtifactCleanupTrigger` cascading delete. |

---

## Phase 5 – Aggregate Ownership Matrix

| Aggregate | Source of Truth | Client Writes | Server Writes | Idempotent | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `reactionCount` | `artifact_reactions` | No (Intents) | Yes (Trigger) | **Level 1** | ⚠ Risk of double-count on trigger retry. |
| `commentCount` | `comments` (subcoll) | Yes (Direct) | No (Current) | **Level 1** | Planned move to Server-Authoritative. |
| `reportCount` | `reports` | Yes (Direct) | Yes (Trigger) | **Level 3** | Uses full aggregation query (Safe but heavy). |
| `playCount` | `artifact_plays` | Yes (Direct) | Yes (Trigger) | **Level 2** | **Proposed SoT**. Throttled via Doc ID. |

> [!WARNING]
> Existing triggers for `reactionCount` lack the `withIdempotency` wrapper. To prevent double-counts during Cloud Function retries, the new `onPlayCreated` **must** use `withIdempotency(context.eventId, ...)`.

---

## Phase 6 – Failure & Edge Case Analysis

| Scenario | Expected Behavior | Consistency Risk |
| :--- | :--- | :--- |
| **Cloud Function Retry** | If `withIdempotency` is used, second run is ignored. | Low |
| **Offline Sync** | Firestore syncs the deterministic ID. Duplicate local events merge into one doc. | None |
| **Anonymous User** | Current code ignores anonymous plays. | **Product Decision** needed (Record with `deviceId`?) |
| **Multiple Devices** | If bucket is shared, user listening on two devices at once counts as 1. | Minimal |
| **Artifact Deletion** | `onArtifactCleanupTrigger` deletes all `artifact_plays` for the ID. | None |

---

## Phase 7 – Security Review

The following rules must be added to `firestore.rules`:

```javascript
match /artifact_plays/{playId} {
  // Pattern: {userId}_{artifactId}_{bucket}
  allow create: if isAuth() && playId.startsWith(request.auth.uid + "_");
  allow read: if isAuth() && playId.startsWith(request.auth.uid + "_");
  allow update, delete: if false;
}
```

---

## Remaining Product Decisions

1.  **Anonymous Play Tracking**: Should we count plays from unauthenticated users? If so, recommend using `deviceId_bucket` instead of `userId_bucket` for ID prefix.
2.  **Bucket Granularity**: Confirm `Hourly` (YYYY-MM-DD-HH) vs `Daily` (YYYY-MM-DD). Hourly is recommended for better UX for repeat listeners.
3.  **Retroactive Count Fix**: Do we need a script to initialize `playCount` for existing artifacts, or start from 0?

---

## Final Assessment

### ⚠ Ready after Minor Design Changes

**Required Architectural Tweaks**:
1.  **Apply Idempotency**: Wrap `onPlayCreated` in `withIdempotency` using `context.eventId`.
2.  **Fix Legacy Triggers**: It is highly recommended to also wrap `onReactionCreated` in `withIdempotency` while modifying `index.ts` to ensure project-wide aggregate integrity.
3.  **Confirm Bucket**: Explicitly define the bucket generator utility in the Functions layer (e.g. `const bucket = new Date().toISOString().slice(0, 13);`).
