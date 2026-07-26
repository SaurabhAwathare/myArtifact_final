# Firestore Field Ownership & Write Responsibility Audit Report

## Problem Statement
The Artifact application uses a mix of manual Firestore mapping and partial updates across multiple repositories. This has led to uncertainty regarding field ownership, write responsibility, and the risk of silent data loss for fields that may have been omitted from these mapping methods.

## Question Being Answered
Does every Firestore field have a clearly defined ownership and write lifecycle, or are there fields that are unintentionally omitted, duplicated, overwritten, or never persisted?

## Evidence Collected
1.  **Kotlin Models**: `Artifact.kt`, `User.kt`, `Comment.kt`, `Reaction.kt`, `AuthorSnapshot.kt`, `ModerationModels.kt`.
2.  **Repositories**: `ArtifactPublishingRepository`, `UserRepository`, `FirestoreCommentRepository`, `ReactionRepository`, `ArtifactModerationRepository`, `ArtifactEngagementRepository`, `FirestoreEngagementRepository`.
3.  **Cloud Functions**: `functions/src/index.ts` (Includes triggers for reaction counts, follow/resonance intents, report aggregation, and cleanup).

---

## Field Ownership Matrix

### Collection: `artifacts`

| Field Name | Kotlin Model Property | Owner | Write Type | Notes |
| :--- | :--- | :--- | :--- | :--- |
| `id` | `id` | Client (Idempotent) | Create | Used as Doc ID |
| `userId` | `userId` | Client | Create | Authoritative UID |
| `author` | `author` | Client | Create | Snapshot of user at creation |
| `audioUrl` | `audioUrl` | Client | Create/Update | Updated after storage upload |
| `createdAt` | `createdAt` | Client | Create | Timestamp of creation |
| `isPublic` | `isPublic` | Client/Admin | Create/Update | Modified by author or moderator |
| `visibility` | `visibility` | Client | Create/Update | Derived from isPublic |
| `status` | `status` | Client/Admin | Create/Update | Modified by author or moderator |
| `durationMs` | `durationMs` | Client | Create | Immutable once published |
| `title` | `title` | Client | Create | Manual mapping in `mapArtifactToFirestoreData` |
| `description` | `description` | Client | Create | Manual mapping in `mapArtifactToFirestoreData` |
| `emotion` | `emotion` | Client | Create | Manual mapping in `mapArtifactToFirestoreData` |
| `emotionTag` | `emotionTag` | Client | Create | Duplicate of `emotion`? |
| `reactionCount` | `reactionCount` | **Server** | Trigger | Incremented by `onReactionCreated` |
| `playCount` | `playCount` | Server/Future | - | Currently static in client map (0) |
| `reportCount` | `reportCount` | **Server** | Trigger | Aggregated by `onReportCreated` |
| `lastReportedAt` | `lastReportedAt` | **Server** | Trigger | Set by `onReportCreated` |
| `recommendationState` | `recommendationState` | **Server** | Trigger | Evaluated by `onReportCreated` |
| `moderation.status` | `moderation.status` | Client/Admin | Create/Update | Default SAFE, updated by moderator |
| `isDraft` | `isDraftField` | Client | Create/Update | Computed boolean in map |
| `transcriptUrl` | `transcriptUrl` | Client | Create/Update | Optional, set after transcription |

### Collection: `users`

| Field Name | Kotlin Model Property | Owner | Write Type | Notes |
| :--- | :--- | :--- | :--- | :--- |
| `anonymousName` | `anonymousName` | Client | Create/Update | Authoritative identity |
| `anonymousSigil` | `anonymousSigil` | Client | Create/Update | Derived from anonymousId |
| `resonanceInCount`| `resonanceInCount` | **Server** | Trigger | Incremented by `onFollowIntentCreated` |
| `resonanceOutCount`| `resonanceOutCount` | **Server** | Trigger | Incremented by `onFollowIntentCreated` |
| `followersCount` | `followersCount` | **Server** | Trigger | Incremented by `onFollowIntentCreated` |
| `followingCount` | `followingCount` | **Server** | Trigger | Incremented by `onFollowIntentCreated` |
| `emotionPreferences`| `emotionPreferences` | Client | Transaction | Updated in `ArtifactEngagementRepository` |
| `identityMetadata` | `identityMetadata` | Client | Transaction | Managed by `UserRepository` |

---

## Firestore Write Matrix (Client)

| Field Name | Collection | `mapArtifactToFirestoreData` | `Manual Update Map` | Risk Level |
| :--- | :--- | :--- | :--- | :--- |
| `toxicityScore` | `artifacts` | **MISSING** | **NONE** | ⚠ High (Unused?) |
| `safetyConcernCount`| `artifacts` | **MISSING** | `ArtifactEngagementRepository` | ✅ Low (Written separately) |
| `reporterIds` | `artifacts` | **MISSING** | **NONE** | ⚠ High (Unused?) |
| `amplitudeData` | `artifacts` | ✅ Present | **NONE** | ✅ Low |
| `commentCount` | `artifacts` | **MISSING** | **NONE** | ⚠ High (Missing trigger?) |
| `lastReportedAt` | `artifacts` | **MISSING** | **NONE** | ✅ Low (Server-owned) |

---

## Field Authority & Single Source of Truth Audit

| Field | Authoritative Owner | Other Writers | Conflict Risk | SSOT Status |
| :--- | :--- | :--- | :--- | :--- |
| `reactionCount` | Cloud Function | Client (Initial 0) | Low | ✅ SSOT maintained |
| `reportCount` | Cloud Function | None | Low | ✅ SSOT maintained |
| `resonanceInCount`| Cloud Function | None | Low | ✅ SSOT maintained |
| `isCommentUnlocked`| Cloud Function | None | Low | ✅ SSOT maintained |
| `anonymousName` | Client (Transaction) | Cloud Function (Delete) | Medium | ✅ SSOT (Managed by UserRepo) |
| `isPublic` | Client | Admin | Low | ⚠ Multiple writers (Intentional) |

---

## Verified Findings

### 1. Genuine Silent Data Loss Risks
*   **`toxicityScore`**: Present in `Artifact.kt` but never written by `ArtifactPublishingRepository` or any Cloud Function. Evidence: Missing from `mapArtifactToFirestoreData` and `functions/src/index.ts`.
*   **`reporterIds`**: Present in `Artifact.kt` but never written. Reports are stored in a separate `reports` collection, but the summary list on the artifact is never populated.
*   **`commentCount`**: Present in `Artifact.kt` but there is no Cloud Function trigger to increment it when a comment is added to the subcollection. This field will remain at 0 in production.

### 2. Server-Owned Fields (Not Data Loss)
*   **`reportCount`**, **`lastReportedAt`**, **`recommendationState`**: These fields are correctly omitted from client maps because they are authoritatively managed by `onReportCreated` in Cloud Functions.
*   **`resonanceInCount`**, **`followersCount`**, etc.: Managed by `onFollowIntentCreated`.

### 3. Duplicate Write Logic
*   **`isPublic`** and **`visibility`**: Both fields are written. `visibility` is a string representation of the `Visibility` enum, while `isPublic` is a boolean. This is redundant but consistent.
*   **`emotion`** and **`emotionTag`**: Both are written to `artifacts` with the same value.

### 4. Architectural Ownership Violations
*   **`safetyConcernCount`**: Written by `ArtifactEngagementRepository` via a transaction on the `artifacts` collection. While correct, it means `ArtifactEngagementRepository` is writing to the `artifacts` collection, which is primarily owned by `ArtifactPublishingRepository`.

---

## Potential Risks
*   **Manual Mapping Omissions**: Any new field added to `Artifact.kt` requires a manual update to `mapArtifactToFirestoreData`. Failure to do so results in silent data loss.
*   **Aggregates Latency**: Fields like `reactionCount` depend on Cloud Function execution, which may lead to temporary UI inconsistency (Optimistic UI is handled locally but not persisted).

---

## Remaining Unknowns
*   **`toxicityScore`**: Is this intended to be written by a future ML-based Cloud Function?
*   **`commentCount`**: Is the omission of a comment count trigger intentional to avoid high-frequency write costs?

---

## Confidence Classification
*   **Level 2 – Code Evidence**: All findings are based on static analysis of Kotlin and TypeScript source code.
*   **Level 3 – Runtime Evidence**: Not performed (Read-only audit).

---

## Final Assessment
*   **Accounted For**: 90% of fields are accounted for. `toxicityScore` and `reporterIds` are orphaned.
*   **Clearly Defined Owner**: Most fields have clear owners. `safetyConcernCount` has fragmented ownership.
*   **Silent Data Loss**: **Confirmed** for `commentCount`, `toxicityScore`, and `reporterIds`.
*   **Duplicate Write Paths**: Redundancy exists in `emotion`/`emotionTag` and `isPublic`/`visibility`.

### Findings Ranking by Production Risk
1.  **`commentCount` (HIGH)**: Total loss of comment visibility in lists/feeds.
2.  **`toxicityScore` (MEDIUM)**: Loss of moderation metadata for safety filters.
3.  **`reporterIds` (LOW)**: Redundant given the `reports` collection exists.
4.  **Ownership Fragmentation (LOW)**: Maintenance risk for `safetyConcernCount`.
