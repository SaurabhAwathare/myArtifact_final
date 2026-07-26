# Firestore Field Ownership & Write Responsibility Audit Walkthrough

I have completed the comprehensive READ-ONLY static investigation of Firestore field ownership and write responsibility.

## Key Accomplishments

### 1. Comprehensive Field Mapping
I audited every property in the `Artifact`, `User`, `Comment`, and `Reaction` models against their write paths in both the Android Kotlin repositories and the Firebase Cloud Functions.

### 2. Identification of Server-Owned Fields
I successfully distinguished between fields that are **Client-owned** (written directly by the app) and **Server-owned/Derived** (written by Cloud Function triggers).
- **Verified Server-Owned**: `reactionCount`, `reportCount`, `resonanceInCount`, `followersCount`, `isCommentUnlocked`.
- **Verified Client-Owned**: `title`, `description`, `emotion`, `visibility`, `anonymousName`.

### 3. Confirmed Silent Data Loss Risks
The investigation confirmed several genuine data loss risks where fields exist in the Kotlin models but are never written to Firestore:
- **`commentCount`**: No Cloud Function or Repository currently increments this field.
- **`toxicityScore`**: Missing from the manual mapping in `ArtifactPublishingRepository`.
- **`reporterIds`**: Never populated in the `artifacts` collection (though source data exists in `reports`).

### 4. Field Authority Matrix
I established a **Single Source of Truth (SSOT)** matrix, identifying which component has authoritative ownership over each field.

## Findings Summary

| Field | Status | Recommendation |
| :--- | :--- | :--- |
| `commentCount` | ❌ Silent Data Loss | Implement Cloud Function trigger on comment creation. |
| `toxicityScore` | ❌ Silent Data Loss | Add to `mapArtifactToFirestoreData`. |
| `reportCount` | ✅ SSOT Maintained | Managed by `onReportCreated` trigger. |
| `reactionCount` | ✅ SSOT Maintained | Managed by `onReactionCreated` trigger. |

## Next Steps
The results of this audit provide the necessary evidence to begin the implementation phase of the Firestore Data Model Integrity task. The next logical step is to address the confirmed silent data loss for `commentCount` and `toxicityScore`.

Refer to the full [investigation_report.artifact.md](file:///F:/Android Project/01/.artifacts/cd0b811e-7b95-4e3b-a1bd-b5ae04ece01e/investigation_report.artifact.md) for detailed evidence and risk rankings.
