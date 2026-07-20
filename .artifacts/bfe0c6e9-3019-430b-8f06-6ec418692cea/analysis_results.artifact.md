# Static Impact Analysis: Allowing `REVIEW_REQUIRED` → `DELETING`

This document summarizes the findings of the static impact analysis for allowing the lifecycle transition from `REVIEW_REQUIRED` to `DELETING`.

## Overview
The investigation established that allowing `REVIEW_REQUIRED` to transition to `DELETING` is necessary to support resilient deletion for local drafts. Currently, local drafts bypass the "soft delete" state (`DELETING`) because the transition is not in the explicit matrix, which weakens recovery if file deletion is interrupted.

## Files Affected

### Core Model & Logic
| File | Impact |
| --- | --- |
| [ArtifactLifecycle.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/model/ArtifactLifecycle.kt) | **[MODIFY]** Update the `transitions` map to include `REVIEW_REQUIRED to setOf(METADATA_REQUIRED, DELETING)`. |
| [DraftDao.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/data/local/DraftDao.kt) | **[VERIFIED]** `markAsDeleting` will now successfully persist the `DELETING` state for drafts in `REVIEW_REQUIRED`, enabling the `RecoveryWorker` to resume failed deletions. |

### Workers & Services
| File | Impact |
| --- | --- |
| [BackupSyncWorker.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/worker/BackupSyncWorker.kt) | **[REGRESSION RISK]** Currently filters only `PUBLISHED`. It will attempt to backup drafts in `DELETING` state. **Required Change**: Update filter to exclude `DELETING` and `DELETED`. |
| [ReviewSessionManager.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/audio/ReviewSessionManager.kt) | **[VIOLATION]** Uses ordinal comparison `lifecycle >= METADATA_REQUIRED`. Since `DELETING` has a higher ordinal (7 vs 3), it will skip updates, which is correct, but violates Invariant 5. |

### UI & UX
| File | Impact |
| --- | --- |
| [PublishingStudioViewModel.kt](file:///F:/Android%20Project/01/app/src/main/java/com/saurabh/artifact/ui/publish/studio/PublishingStudioViewModel.kt) | **[INCONSISTENCY]** `StudioStep.fromLifecycle` maps unknown states (like `DELETING`) to `REVIEW`. If a draft is deleted while the Studio is open, the UI will stay in the Review step instead of dismissing. |

## Required Test Updates

### Unit Tests
- **[LifecycleTransitionTest.kt](file:///F:/Android%20Project/01/app/src/test/java/com/saurabh/artifact/model/LifecycleTransitionTest.kt)**: Add `assertTrue(ArtifactLifecycle.REVIEW_REQUIRED.canTransitionTo(ArtifactLifecycle.DELETING))`.
- **[DraftDeletionManagerTest.kt](file:///F:/Android%20Project/01/app/src/test/java/com/saurabh/artifact/audio/DraftDeletionManagerTest.kt)**: Verify that `markAsDeleting` successfully updates the DB state for `REVIEW_REQUIRED` drafts.

### Integration Tests
- **[RecoveryWorkerTest.kt](file:///F:/Android%20Project/01/app/src/test/java/com/saurabh/artifact/worker/RecoveryWorkerTest.kt)**: Add a case where a `REVIEW_REQUIRED` draft is in `DELETING` state and ensure `RecoveryWorker` resumes its deletion.

## Required Documentation Updates
- **[ADR-0002.md](file:///F:/Android%20Project/01/docs/adr/ADR-0002.md)**: Update the "Decision" or "Context" section to reflect the inclusion of draft deletion paths.
- **[PublishingFlowInvariants.md](file:///F:/Android%20Project/01/docs/architecture/PublishingFlowInvariants.md)**: No change needed to the invariant text, but the implementation must now adhere to it for this new path.

## Potential Regressions
> [!WARNING]
> **Backup Interference**: If a draft is marked for deletion, `BackupSyncWorker` may still attempt to encrypt and upload it to Firebase Storage if the worker runs before the file purge completes. This is a waste of bandwidth and creates "zombie" backups.

> [!IMPORTANT]
> **Ordinal Assumptions**: `ReviewSessionManager` relies on the fact that terminal/deletion states have higher ordinal values than `METADATA_REQUIRED`. If the enum order changes, this idempotency guard will fail.

## Confidence Level
**95% (Code Evidence only)**
The code paths for deletion and recovery are well-defined. The main risks are in peripheral workers (`BackupSyncWorker`) and architectural hygiene (`ReviewSessionManager`).
